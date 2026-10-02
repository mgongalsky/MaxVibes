package com.maxvibes.plugin.service

import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.PsiFailureReport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** File-based tests with independent project loggers; no running IDE required. */
class PsiFailureReportWriterTest {
    @TempDir
    lateinit var projectRoot: File

    private class RecordingLogger : LoggerPort {
        val infos = mutableListOf<Map<String, Any?>?>()
        val warnings = mutableListOf<Throwable?>()
        override fun debug(tag: String, msg: String, data: Map<String, Any?>?) = Unit
        override fun info(tag: String, msg: String, data: Map<String, Any?>?) {
            infos += data
        }

        override fun warn(tag: String, msg: String, ex: Throwable?, data: Map<String, Any?>?) {
            warnings += ex
        }

        override fun error(tag: String, msg: String, ex: Throwable?, data: Map<String, Any?>?) = Unit
    }

    private fun writer(
        root: File = projectRoot,
        logger: RecordingLogger = RecordingLogger(),
        session: String = "log-session"
    ) = PsiFailureReportWriter(
        root.absolutePath, logger, session, File(root, ".maxvibes/plugin.log").absolutePath
    )

    private fun report(
        kind: PsiFailureReport.Kind = PsiFailureReport.Kind.APPLY,
        sessionId: String = "s-1",
        sections: List<PsiFailureReport.Section> = listOf(
            PsiFailureReport.Section("Отказ 1", "error: ElementNotFound")
        )
    ) = PsiFailureReport(kind, sessionId, "CLAUDE_CODE", "отказов применения: 1", sections)

    @Test
    fun `report lands under maxvibes reports psi`() {
        val path = writer().report(report())
        assertNotNull(path)
        val file = File(path!!)
        assertTrue(file.exists())
        assertEquals(File(projectRoot, ".maxvibes/reports/psi").absolutePath, file.parentFile.absolutePath)
    }

    @Test
    fun `file name starts with a timestamp and carries kind and session`() {
        val path = writer().report(report(kind = PsiFailureReport.Kind.PARSE, sessionId = "chat/42"))
        val name = File(path!!).name
        assertTrue(name.matches(Regex("\\d{8}-\\d{6}-\\d{3}_parse_chat-42\\.md")), name)
    }

    @Test
    fun `section bodies are written in full`() {
        val body = "x".repeat(20_000)
        val path = writer().report(report(sections = listOf(PsiFailureReport.Section("Правка", body))))
        val text = File(path!!).readText()
        assertTrue(text.contains("## Правка"))
        assertTrue(text.contains(body), "report body was truncated")
    }

    @Test
    fun `interleaved projects keep their own metadata and logger`() {
        val firstRoot = File(projectRoot, "PoreSizer")
        val secondRoot = File(projectRoot, "MALDI_Studio")
        val firstLogger = RecordingLogger()
        val secondLogger = RecordingLogger()
        val first = writer(firstRoot, firstLogger, "log-pore")
        val second = writer(secondRoot, secondLogger, "log-maldi")
        val firstPath = first.report(report(sessionId = "first"))!!
        val secondPath = second.report(report(sessionId = "second"))!!
        val lastPath = first.report(report(sessionId = "third"))!!
        for (path in listOf(firstPath, lastPath)) {
            val text = File(path).readText()
            assertTrue(text.contains("- plugin log session: log-pore"))
            assertTrue(text.contains("- transcript: " + File(firstRoot, ".maxvibes/plugin.log").absolutePath))
            assertFalse(text.contains("MALDI_Studio"))
        }
        val text = File(secondPath).readText()
        assertTrue(text.contains("- plugin log session: log-maldi"))
        assertTrue(text.contains("- transcript: " + File(secondRoot, ".maxvibes/plugin.log").absolutePath))
        assertFalse(text.contains("PoreSizer"))
        assertEquals(listOf(File(firstPath).name, File(lastPath).name), firstLogger.infos.map { it?.get("file") })
        assertEquals(listOf(File(secondPath).name), secondLogger.infos.map { it?.get("file") })
    }

    @Test
    fun `write failure warns only its own project once`() {
        val blocked = File(projectRoot, "blocked").apply { writeText("file instead of directory") }
        val failingLogger = RecordingLogger()
        val otherLogger = RecordingLogger()
        val failing = writer(blocked, failingLogger)
        assertNull(failing.report(report()))
        assertNull(failing.report(report()))
        assertNotNull(writer(File(projectRoot, "other"), otherLogger).report(report()))
        assertEquals(1, failingLogger.warnings.size)
        assertNotNull(failingLogger.warnings.single())
        assertTrue(otherLogger.warnings.isEmpty())
    }
}
