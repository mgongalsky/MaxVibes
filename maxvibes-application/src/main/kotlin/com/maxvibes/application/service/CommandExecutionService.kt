package com.maxvibes.application.service

import com.maxvibes.application.port.input.ExecuteCommandUseCase
import com.maxvibes.application.port.output.CommandRunnerPort
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.domain.model.command.CommandExecution
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.command.CommandStatus

/**
 * Выполнение shell-команд от LLM с мягкой классификацией.
 * Ничего не блокирует — только предупреждает; решение за пользователем.
 */
class CommandExecutionService(
    private val runner: CommandRunnerPort,
    private val logger: LoggerPort? = null
) : ExecuteCommandUseCase {

    override suspend fun execute(request: CommandRequest): CommandExecution {
        logger?.info(TAG, "Executing: '${request.command.take(120)}'")
        val execution = runner.run(request)
        logger?.info(
            TAG,
            "Finished: status=${execution.status}, exit=${execution.exitCode}, ${execution.durationMs}ms"
        )
        return execution
    }

    override fun warningsFor(request: CommandRequest): List<String> {
        val warnings = mutableListOf<String>()
        if (request.reason.isNullOrBlank()) {
            warnings += "LLM не объяснил, зачем нужна команда"
        }
        if (mutatesFiles(request.command)) {
            warnings += "Похоже, команда меняет файлы напрямую — обычно это делается PSI-модификациями"
        }
        if (DESTRUCTIVE_PATTERNS.any { it.containsMatchIn(request.command) }) {
            warnings += "Деструктивная команда — проверь перед запуском"
        }
        return warnings
    }

    override fun formatForLlm(execution: CommandExecution, tailLines: Int): String {
        val req = execution.request
        return buildString {
            appendLine("Command: ${req.command}")
            if (execution.status == CommandStatus.DECLINED) {
                append("Status: DECLINED by user")
                execution.declineComment?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
                appendLine()
                appendLine("Do not retry this command unless the user asks for it.")
            } else {
                appendLine(
                    "Status: ${execution.status}, exit code: ${execution.exitCode ?: "n/a"}, " +
                            "duration: ${execution.durationMs} ms"
                )
                val lines = execution.output.lines()
                if (lines.size > tailLines) {
                    appendLine("Output (last $tailLines of ${lines.size} lines):")
                    appendLine(lines.takeLast(tailLines).joinToString("\n"))
                } else {
                    appendLine("Output:")
                    appendLine(execution.output)
                }
                if (mutatesFiles(req.command)) {
                    appendLine(
                        "Note: this command modified files directly. Prefer PSI modifications " +
                                "for code changes; use shell edits only as a workaround when a PSI operation fails."
                    )
                }
            }
        }.trimEnd()
    }

    /** Эвристика для бейджа и напоминания, НЕ блокировка. False positives допустимы. */
    private fun mutatesFiles(command: String): Boolean =
        FILE_MUTATION_PATTERNS.any { it.containsMatchIn(command) }

    companion object {
        private const val TAG = "CommandExecutionService"

        private val FILE_MUTATION_PATTERNS = listOf(
            Regex(""">{1,2}\s*[^&\s]"""),                          // редиректы в файл (2>&1 не ловим)
            Regex("""\bsed\b[^|]*\s-i\b"""),
            Regex("""\b(Set-Content|Out-File|Add-Content|Tee-Object)\b""", RegexOption.IGNORE_CASE),
            Regex("""^\s*patch\b"""),
            Regex("""\b(rm|del|erase|Remove-Item)\b\s""", RegexOption.IGNORE_CASE),
            Regex("""\b(mv|Move-Item|Rename-Item)\b\s""", RegexOption.IGNORE_CASE)
        )

        private val DESTRUCTIVE_PATTERNS = listOf(
            Regex("""\brm\s+-\w*r"""),
            Regex("""Remove-Item\b.*-Recurse""", RegexOption.IGNORE_CASE),
            Regex("""\bgit\s+(reset\s+--hard|clean\s+-\w*f|checkout\s+--|push\s+--force)""")
        )
    }
}