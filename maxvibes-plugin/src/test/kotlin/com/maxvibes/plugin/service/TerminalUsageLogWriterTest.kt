package com.maxvibes.plugin.service

import com.maxvibes.application.port.output.TerminalUsageEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TerminalUsageLogWriterTest {

    @TempDir
    lateinit var projectDir: File

    private fun logDir() = File(projectDir, ".maxvibes/reports/terminal")

    @Test
    fun `commands of one chat pile up in one file`() {
        val writer = TerminalUsageLogWriter(projectDir.absolutePath)

        writer.record(TerminalUsageEntry("s1", "CLAUDE_CODE", "git status", "посмотреть изменения"))
        writer.record(TerminalUsageEntry("s1", "CLAUDE_CODE", "gradlew test", "прогнать тесты"))

        val files = logDir().listFiles().orEmpty()
        assertEquals(1, files.size, "один чат — один файл")
        val text = files.single().readText()
        assertTrue(text.contains("git status"), text)
        assertTrue(text.contains("gradlew test"), text)
        assertTrue(text.contains("прогнать тесты"), text)
        assertEquals(
            1,
            text.lines().count { it.startsWith("# ") },
            "заголовок пишется один раз: вторая запись — дозапись, а не перезапись"
        )
    }

    @Test
    fun `every chat gets its own file`() {
        val writer = TerminalUsageLogWriter(projectDir.absolutePath)

        writer.record(TerminalUsageEntry("s1", "API", "git log", null))
        writer.record(TerminalUsageEntry("s2", "API", "git log", null))

        assertEquals(2, logDir().listFiles().orEmpty().size)
    }

    @Test
    fun `missing reason is stated instead of left blank`() {
        val writer = TerminalUsageLogWriter(projectDir.absolutePath)

        writer.record(TerminalUsageEntry("s3", "CLIPBOARD", "npm install", null))

        val text = logDir().listFiles().orEmpty().single().readText()
        assertTrue(text.contains("мотивировки не было"), text)
    }
}
