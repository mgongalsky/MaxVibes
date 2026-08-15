package com.maxvibes.plugin.check

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.maxvibes.application.port.output.CheckRunnerPort
import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckIssue
import com.maxvibes.domain.model.check.CheckKind
import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.check.CheckStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Прогон тестов существующей run configuration средствами IDE.
 *
 * Не привязан к языку: конфигурация запускается через [ProgramRunnerUtil], а
 * результаты снимаются с общей тестовой шины, поэтому в PyCharm тот же код
 * гоняет pytest. Пользователь при этом видит обычное дерево тестов, а агент —
 * список упавших кейсов вместо вывода в консоль.
 *
 * Своя конфигурация не создаётся намеренно: это потребовало бы API JUnit- или
 * Gradle-плагина, то есть ещё двух опциональных зависимостей ради случая, уже
 * закрытого готовой конфигурацией в любом живом проекте.
 */
class RunConfigurationTestCheckRunner(private val project: Project) : CheckRunnerPort {

    override fun supports(kind: CheckKind): Boolean = kind == CheckKind.TESTS

    override suspend fun run(request: CheckRequest): CheckExecution {
        val settings = resolveSettings(request.scope)
            ?: return CheckExecution(
                request = request,
                status = CheckStatus.ERROR,
                rawOutput = buildString {
                    append(
                        request.scope?.let { "No run configuration named '$it'." }
                            ?: "No run configuration is selected in the IDE."
                    )
                    append(" Available: ").append(availableConfigurations())
                }
            )

        val startedAt = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(request.timeoutSec * 1000L) { execute(settings) }
        val durationMs = System.currentTimeMillis() - startedAt

        if (outcome == null) {
            return CheckExecution(
                request = request,
                status = CheckStatus.TIMEOUT,
                durationMs = durationMs,
                rawOutput = "Tests did not finish within ${request.timeoutSec}s; the run continues in the IDE."
            )
        }

        return CheckExecution(
            request = request,
            status = if (outcome.failures.isEmpty()) CheckStatus.PASSED else CheckStatus.FAILED,
            issues = outcome.failures,
            testsTotal = outcome.total,
            testsFailed = outcome.failures.size,
            durationMs = durationMs
        )
    }

    /**
     * Подписка ловит любой прогон тестов в проекте, в том числе запущенный
     * пользователем вручную. Осознанный компромисс: точная привязка к своему
     * ExecutionEnvironment требует возни с дескрипторами, а окно между запуском
     * и завершением — секунды.
     */
    private suspend fun execute(settings: RunnerAndConfigurationSettings): Outcome =
        suspendCancellableCoroutine { continuation ->
            val connection = project.messageBus.connect()
            val failures = mutableListOf<CheckIssue>()

            connection.subscribe(
                SMTRunnerEventsListener.TEST_STATUS,
                object : SMTRunnerEventsAdapter() {
                    override fun onTestFailed(test: SMTestProxy) {
                        failures += toIssue(test)
                    }

                    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                        connection.disconnect()
                        if (continuation.isActive) {
                            continuation.resume(
                                Outcome(
                                    failures = failures.toList(),
                                    total = testsRoot.allTests.count { it.isLeaf }
                                )
                            )
                        }
                    }
                }
            )
            continuation.invokeOnCancellation { connection.disconnect() }

            ApplicationManager.getApplication().invokeLater {
                ProgramRunnerUtil.executeConfiguration(
                    settings,
                    DefaultRunExecutor.getRunExecutorInstance()
                )
            }
        }

    private fun resolveSettings(scope: String?): RunnerAndConfigurationSettings? {
        val runManager = RunManager.getInstance(project)
        return if (scope == null) {
            runManager.selectedConfiguration
        } else {
            runManager.allSettings.firstOrNull { it.name.equals(scope, ignoreCase = true) }
        }
    }

    private fun availableConfigurations(): String =
        RunManager.getInstance(project).allSettings
            .joinToString(", ") { it.name }
            .ifBlank { "none" }

    private fun toIssue(test: SMTestProxy): CheckIssue = CheckIssue(
        message = test.errorMessage?.trim()?.takeIf { it.isNotBlank() } ?: "Test failed",
        testName = listOfNotNull(test.parent?.name, test.name).joinToString("."),
        // Причина падения почти всегда в первых строках; полный трейс съедает контекст.
        details = test.stacktrace?.trim()?.lineSequence()?.take(10)?.joinToString("\n")
    )

    private class Outcome(
        val failures: List<CheckIssue>,
        val total: Int
    )
}
