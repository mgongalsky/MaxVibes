package com.maxvibes.application.service

import com.maxvibes.application.port.input.RunCheckUseCase
import com.maxvibes.application.port.output.CheckRunnerPort
import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckIssue
import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.check.CheckStatus
import com.maxvibes.domain.model.check.IssueSeverity
import com.maxvibes.domain.model.check.CheckCancellation
import com.maxvibes.domain.model.check.CheckProgressSink

/**
 * Выполнение проверок, запрошенных агентом, и превращение результата в текст для него.
 *
 * Диагностика падения кладётся в [CheckExecution.rawOutput], а не в лог: оттуда
 * её видит и пользователь в чате, и агент, который может отреагировать сам.
 */
class CheckExecutionService(
    private val runner: CheckRunnerPort
) : RunCheckUseCase {

    override suspend fun run(
        request: CheckRequest,
        progress: CheckProgressSink,
        cancellation: CheckCancellation
    ): CheckExecution {
        if (!runner.supports(request.kind)) {
            return CheckExecution(
                request = request,
                status = CheckStatus.UNSUPPORTED,
                rawOutput = "This IDE has no runner for ${request.kind} checks."
            )
        }
        val startedAt = System.currentTimeMillis()
        return try {
            runner.run(request, progress, cancellation)
        } catch (e: Exception) {
            // Убитый процесс тестов почти всегда прилетает сюда исключением;
            // без этой ветки пользователь увидел бы "Failed to start" в ответ
            // на собственное нажатие Cancel.
            val cancelled = cancellation.isCancelled
            CheckExecution(
                request = request,
                status = if (cancelled) CheckStatus.CANCELLED else CheckStatus.ERROR,
                durationMs = System.currentTimeMillis() - startedAt,
                rawOutput = if (cancelled) "Cancelled by user." else e.message ?: e::class.simpleName.orEmpty()
            )
        }
    }

    override fun formatForLlm(execution: CheckExecution, maxIssues: Int): String = buildString {
        val request = execution.request
        append("=== CHECK ").append(request.kind)
        request.scope?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
        append(" — ").append(execution.status)
        if (execution.durationMs > 0) {
            append(" in ").append(execution.durationMs / 1000).append("s")
        }
        appendLine(" ===")

        execution.declineComment?.takeIf { it.isNotBlank() }?.let {
            appendLine("User comment: $it")
        }

        execution.testsTotal?.let {
            appendLine("Tests: $it total, ${execution.testsFailed ?: 0} failed")
        }

        val shown = execution.issues.take(maxIssues)
        shown.forEach { appendLine(formatIssue(it)) }
        val hidden = execution.issues.size - shown.size
        if (hidden > 0) {
            appendLine("...and $hidden more")
        }

        if (execution.issues.isEmpty() && execution.rawOutput.isNotBlank()) {
            appendLine(execution.rawOutput.trim())
        }
    }.trim()

    private fun formatIssue(issue: CheckIssue): String = buildString {
        append("- ")
        if (issue.severity == IssueSeverity.WARNING) append("[warn] ")
        issue.testName?.takeIf { it.isNotBlank() }?.let { append(it).append(": ") }
        issue.filePath?.takeIf { it.isNotBlank() }?.let { path ->
            append(path)
            issue.line?.let { append(':').append(it) }
            append(" — ")
        }
        append(issue.message.trim())
        issue.details?.takeIf { it.isNotBlank() }?.let { details ->
            append('\n')
            append(details.trim().lineSequence().joinToString("\n") { "    $it" })
        }
    }
}
