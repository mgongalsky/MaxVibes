package com.maxvibes.plugin.check

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.task.ProjectTaskManager
import com.intellij.util.messages.MessageBusConnection
import com.maxvibes.application.port.output.CheckRunnerPort
import com.maxvibes.domain.model.check.CheckCancellation
import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckIssue
import com.maxvibes.domain.model.check.CheckKind
import com.maxvibes.domain.model.check.CheckProgress
import com.maxvibes.domain.model.check.CheckProgressSink
import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.check.CheckStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Сборка проекта средствами IDE — то же самое, что Build Project в меню.
 *
 * Идём через [ProjectTaskManager], а не через `CompilerManager`: последний
 * дёргает встроенный сборщик JPS напрямую и про делегирование сборки в Gradle
 * не знает. В Android Studio это означало сборку не тем сборщиком — с чужими
 * ошибками и без всякой связи с настоящим результатом.
 *
 * У результата [ProjectTaskManager] есть только флаги, поэтому диагностику
 * собираем сами. Gradle-сборка отдаёт её через [BuildOutputCollector], JPS —
 * своими `CompilerMessage` в [CompilerTopics.COMPILATION_STATUS]. Какой канал
 * сработает, зависит от настройки делегирования в конкретном проекте, и
 * слушать оба дешевле, чем угадывать.
 *
 * Класс ссылается на compiler API, которого нет в IDE без JVM-поддержки,
 * поэтому создаётся только через [CheckRunnerProvider].
 */
class JvmBuildCheckRunner(private val project: Project) : CheckRunnerPort {

    override fun supports(kind: CheckKind): Boolean = kind == CheckKind.BUILD

    override suspend fun run(
        request: CheckRequest,
        progress: CheckProgressSink,
        cancellation: CheckCancellation
    ): CheckExecution {
        val startedAt = System.currentTimeMillis()
        progress.publish(CheckProgress("Compiling " + (request.scope ?: "whole project")))
        val outcome = withTimeoutOrNull(request.timeoutSec * 1000L) { compile(request.scope, cancellation) }
        val durationMs = System.currentTimeMillis() - startedAt

        if (outcome == null) {
            return CheckExecution(
                request = request,
                status = CheckStatus.TIMEOUT,
                durationMs = durationMs,
                rawOutput = "Build did not finish within ${request.timeoutSec}s; it keeps running in the IDE."
            )
        }

        val status = when {
            cancellation.isCancelled -> CheckStatus.CANCELLED
            outcome.failure != null -> CheckStatus.ERROR
            outcome.issues.isNotEmpty() -> CheckStatus.FAILED
            else -> CheckStatus.PASSED
        }
        return CheckExecution(
            request = request,
            status = status,
            issues = outcome.issues,
            durationMs = durationMs,
            rawOutput = outcome.rawOutput
        )
    }

    private suspend fun compile(scope: String?, cancellation: CheckCancellation): Outcome =
        suspendCancellableCoroutine { continuation ->
            val settled = AtomicBoolean(false)
            val diagnostics = BuildDiagnostics()

            fun finish(outcome: Outcome) {
                if (!settled.compareAndSet(false, true)) return
                diagnostics.stop()
                if (continuation.isActive) continuation.resume(outcome)
            }

            // Прервать сборку нечем: ProjectTaskManager, как и CompilerManager,
            // не отдаёт хендла. Поэтому Cancel отпускает ожидание с честной
            // оговоркой — это лучше и кнопки, которая ничего не делает, и вечно
            // висящей задачи.
            cancellation.onCancel {
                finish(Outcome(failure = "Cancelled by user; the IDE build keeps running."))
            }

            diagnostics.start()

            // Модальность задаётся явно: по умолчанию invokeLater наследует её от
            // фоновой задачи, из которой пришёл автозапуск чека, и тогда runnable
            // ждёт чужой модальности, которой на EDT уже нет — сборка не стартует.
            ApplicationManager.getApplication().invokeLater({
                val manager = ProjectTaskManager.getInstance(project)
                val promise = if (scope == null) {
                    manager.buildAllModules()
                } else {
                    val module = ModuleManager.getInstance(project).findModuleByName(scope)
                    if (module == null) {
                        finish(Outcome(failure = "Unknown module: $scope"))
                        return@invokeLater
                    }
                    manager.build(module)
                }
                promise
                    .onSuccess { result ->
                        finish(
                            if (result.isAborted) Outcome(failure = "Build was aborted.")
                            else diagnostics.toOutcome(result.hasErrors())
                        )
                    }
                    .onError { error ->
                        finish(Outcome(failure = "Build could not be started: " + (error.message ?: error.toString())))
                    }
            }, ModalityState.NON_MODAL)
        }

    private fun toIssue(message: CompilerMessage): CheckIssue {
        val descriptor = message.navigatable as? OpenFileDescriptor
        return CheckIssue(
            message = message.message.trim(),
            filePath = message.virtualFile?.path?.let { toProjectRelativePath(project, it) },
            line = descriptor?.line?.plus(1)
        )
    }

    /** Слушает оба канала сборки одновременно — делегированный Gradle и встроенный JPS. */
    private inner class BuildDiagnostics {

        private val jpsIssues = mutableListOf<CheckIssue>()
        private val collector = BuildOutputCollector(project)
        private var connection: MessageBusConnection? = null

        fun start() {
            val bus = project.messageBus.connect()
            bus.subscribe(CompilerTopics.COMPILATION_STATUS, object : CompilationStatusListener {
                override fun compilationFinished(
                    aborted: Boolean,
                    errors: Int,
                    warnings: Int,
                    compileContext: CompileContext
                ) {
                    collectJps(compileContext)
                }
            })
            connection = bus
            collector.start()
        }

        fun stop() {
            connection?.disconnect()
            connection = null
            collector.stop()
        }

        fun toOutcome(hasErrors: Boolean): Outcome {
            if (!hasErrors) return Outcome()
            val issues = collectIssues()
            if (issues.isNotEmpty()) return Outcome(issues = issues, rawOutput = collector.outputTail())
            // Ошибки были, а распознать нечего: зелёная проверка здесь была бы
            // прямым враньём, поэтому падаем и отдаём агенту хвост лога.
            return Outcome(
                issues = listOf(
                    CheckIssue(
                        message = "Build failed; the compiler output could not be parsed — see the Build tool window.",
                        filePath = null,
                        line = null
                    )
                ),
                rawOutput = collector.outputTail()
            )
        }

        private fun collectJps(context: CompileContext) {
            // Только ERROR: предупреждений в живом проекте сотни, и они утопят те
            // три настоящие ошибки, ради которых проверка и запускалась.
            val messages = context.getMessages(CompilerMessageCategory.ERROR).map { toIssue(it) }
            synchronized(jpsIssues) { jpsIssues += messages }
        }

        private fun collectIssues(): List<CheckIssue> {
            val fromJps = synchronized(jpsIssues) { jpsIssues.toList() }
            return (collector.issues() + fromJps).distinct().take(MAX_REPORTED_ISSUES)
        }
    }

    private class Outcome(
        val issues: List<CheckIssue> = emptyList(),
        /** Непустое — сборку не удалось довести до результата; текст уходит агенту. */
        val failure: String? = null,
        /** Отдельно от [failure]: провал сборки — это FAILED, а не ERROR. */
        val rawOutput: String = failure.orEmpty()
    )
}
