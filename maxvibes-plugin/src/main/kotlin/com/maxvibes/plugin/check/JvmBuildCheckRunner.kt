package com.maxvibes.plugin.check

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleManager
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
 * Сборка проекта средствами IDE — то же самое, что Build Project в меню.
 *
 * В проекте с делегированием сборки вызов всё равно уходит в Gradle, но
 * результат приходит структурно: [CompilerMessage] с файлом и позицией вместо
 * текстового лога, который агенту пришлось бы разбирать самому.
 *
 * Класс ссылается на compiler API, которого нет в IDE без JVM-поддержки,
 * поэтому создаётся только через [CheckRunnerProvider].
 */
class JvmBuildCheckRunner(private val project: Project) : CheckRunnerPort {

    override fun supports(kind: CheckKind): Boolean = kind == CheckKind.BUILD

    override suspend fun run(request: CheckRequest): CheckExecution {
        val startedAt = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(request.timeoutSec * 1000L) { compile(request.scope) }
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
            outcome.failure != null -> CheckStatus.ERROR
            outcome.issues.isNotEmpty() -> CheckStatus.FAILED
            else -> CheckStatus.PASSED
        }
        return CheckExecution(
            request = request,
            status = status,
            issues = outcome.issues,
            durationMs = durationMs,
            rawOutput = outcome.failure.orEmpty()
        )
    }

    private suspend fun compile(scope: String?): Outcome =
        suspendCancellableCoroutine { continuation ->
            ApplicationManager.getApplication().invokeLater {
                val manager = CompilerManager.getInstance(project)
                val callback = CompileStatusNotification { aborted, _, _, context ->
                    continuation.resume(
                        if (aborted) Outcome(failure = "Build was aborted.")
                        else Outcome(issues = collectErrors(context))
                    )
                }
                if (scope == null) {
                    manager.make(callback)
                    return@invokeLater
                }
                val module = ModuleManager.getInstance(project).findModuleByName(scope)
                if (module == null) {
                    continuation.resume(Outcome(failure = "Unknown module: $scope"))
                } else {
                    manager.make(module, callback)
                }
            }
        }

    /**
     * Только ERROR: предупреждений в живом проекте сотни, и они утопят те три
     * настоящие ошибки, ради которых проверка и запускалась.
     */
    private fun collectErrors(context: CompileContext): List<CheckIssue> =
        context.getMessages(CompilerMessageCategory.ERROR).map { toIssue(it) }

    private fun toIssue(message: CompilerMessage): CheckIssue {
        val descriptor = message.navigatable as? OpenFileDescriptor
        return CheckIssue(
            message = message.message.trim(),
            filePath = message.virtualFile?.path?.let { toProjectRelative(it) },
            line = descriptor?.line?.plus(1)
        )
    }

    private fun toProjectRelative(path: String): String {
        val base = project.basePath ?: return path
        return path.removePrefix(base).removePrefix("/")
    }

    private class Outcome(
        val issues: List<CheckIssue> = emptyList(),
        /** Непустое — сборку не удалось довести до результата; текст уходит агенту. */
        val failure: String? = null
    )
}
