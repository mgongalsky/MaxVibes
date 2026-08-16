package com.maxvibes.plugin.service

import com.maxvibes.application.port.output.TerminalUsageEntry
import com.maxvibes.application.port.output.TerminalUsageLogPort
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Ведёт журнал обращений к терминалу в `<project>/.maxvibes/reports/terminal`.
 *
 * Один файл на чат, а не на обращение: разбирать надо не отдельный вызов, а
 * привычку модели, и она видна только когда вызовы одного диалога лежат подряд.
 * Запись идёт дозаписью в конец, поэтому журнал переживает и падение IDE, и
 * длинную сессию, не переписываясь целиком.
 *
 * Пишется намеренно скупо: время, режим, команда, мотивировка. Вывод, ради
 * которого журнал заведён, звучит как «за неделю пять раз звали git status» —
 * для него не нужны ни вывод команды, ни код возврата.
 */
class TerminalUsageLogWriter(projectBasePath: String) : TerminalUsageLogPort {

    private companion object {
        private const val TAG = "TerminalUsageLog"
        private const val NAME_PART_MAX = 40
    }

    private val logDir = File(projectBasePath, ".maxvibes/reports/terminal")
    private val stampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Volatile
    private var warned = false

    override fun record(entry: TerminalUsageEntry) {
        try {
            logDir.mkdirs()
            val file = File(logDir, safeNamePart(entry.sessionId) + ".md")
            if (!file.exists()) {
                file.writeText("# Обращения к терминалу, чат " + entry.sessionId + "\n", StandardCharsets.UTF_8)
            }
            file.appendText(render(entry, LocalDateTime.now()), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            if (!warned) {
                warned = true
                MaxVibesLogger.warn(TAG, "cannot write terminal usage log", ex = e)
            }
        }
    }

    private fun render(entry: TerminalUsageEntry, now: LocalDateTime): String = buildString {
        appendLine()
        appendLine("## " + now.format(stampFormatter) + " · " + entry.provider)
        appendLine()
        appendLine("```")
        appendLine(entry.command)
        appendLine("```")
        appendLine()
        appendLine(entry.reason?.takeIf { it.isNotBlank() } ?: "_мотивировки не было_")
    }

    /** Идентификатор чата приходит из модели данных, а не из файловой системы. */
    private fun safeNamePart(raw: String): String {
        val cleaned = raw.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-'
        }.joinToString("").trim('-')
        return cleaned.take(NAME_PART_MAX).ifBlank { "unknown" }
    }
}
