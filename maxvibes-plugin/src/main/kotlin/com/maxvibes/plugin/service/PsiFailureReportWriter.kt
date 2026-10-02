package com.maxvibes.plugin.service

import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.PsiFailureReport
import com.maxvibes.application.port.output.PsiFailureReportPort
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Складывает отчёты о сбоях протокола правок в `<project>/.maxvibes/reports/psi`.
 *
 * Один markdown-файл на инцидент, имя начинается с метки времени.
 * Текст правки и сырой JSON сохраняются полностью. Ротации отчётов нет.
 * Логгер и метаданные принадлежат проекту, для которого создаётся отчёт.
 */
class PsiFailureReportWriter(
    projectBasePath: String,
    private val logger: LoggerPort,
    private val logSessionId: String,
    private val logFilePath: String
) : PsiFailureReportPort {

    private companion object {
        private const val TAG = "PsiFailureReport"

        /** Ограда из четырёх кавычек: внутри секций встречаются тройные. */
        private const val FENCE = "````"
        private const val NAME_PART_MAX = 40
    }

    private val reportDir = File(projectBasePath, ".maxvibes/reports/psi")
    private val stampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val headerFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @Volatile
    private var warned = false

    override fun report(report: PsiFailureReport): String? {
        val now = LocalDateTime.now()
        return try {
            reportDir.mkdirs()
            val file = File(reportDir, fileName(report, now))
            file.writeText(render(report, now), StandardCharsets.UTF_8)
            logger.info(
                TAG,
                "psi failure report written",
                mapOf("kind" to report.kind.name, "file" to file.name)
            )
            file.absolutePath
        } catch (e: Exception) {
            if (!warned) {
                warned = true
                logger.warn(TAG, "cannot write psi failure report", ex = e)
            }
            null
        }
    }

    private fun fileName(report: PsiFailureReport, now: LocalDateTime): String =
        now.format(stampFormatter) +
                "_" + report.kind.name.lowercase() +
                "_" + safeNamePart(report.sessionId) +
                ".md"

    private fun render(report: PsiFailureReport, now: LocalDateTime): String = buildString {
        // Без поднятой платформы PluginManagerCore способен бросить Error.
        val pluginVersion = runCatching {
            PluginManagerCore.getPlugin(PluginId.getId("com.maxvibes.plugin"))?.version
        }.getOrNull() ?: "unknown"
        appendLine("# " + report.kind.name + ": " + report.summary)
        appendLine()
        appendLine("- time: " + now.format(headerFormatter))
        appendLine("- session: " + report.sessionId)
        appendLine("- provider: " + report.provider)
        appendLine("- plugin: " + pluginVersion)
        appendLine("- plugin log session: " + logSessionId)
        appendLine("- transcript: " + logFilePath)
        report.sections.forEach { section ->
            appendLine()
            appendLine("## " + section.title)
            appendLine()
            appendLine(FENCE)
            appendLine(section.body)
            appendLine(FENCE)
        }
    }

    /** Белый список символов: в сводке живёт текст ошибки PSI со слэшами и двоеточиями. */
    private fun safeNamePart(raw: String): String {
        val cleaned = raw.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-'
        }.joinToString("").trim('-')
        return cleaned.take(NAME_PART_MAX).ifBlank { "unknown" }
    }
}
