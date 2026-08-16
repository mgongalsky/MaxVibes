package com.maxvibes.plugin.service

import com.maxvibes.application.port.output.PsiFailureReport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [PsiFailureReportWriter].
 *
 * No IDE, no mocks — the writer only needs a project root.
 */
class PsiFailureReportWriterTest {

    @TempDir
    lateinit var projectRoot: File

    private fun report(
        kind: PsiFailureReport.Kind = PsiFailureReport.Kind.APPLY,
        sessionId: String = "s-1",
        sections: List<PsiFailureReport.Section> = listOf(
            PsiFailureReport.Section("Отказ 1", "error: ElementNotFound")
        )
    ) = PsiFailureReport(
        kind = kind,
        sessionId = sessionId,
        provider = "CLAUDE_CODE",
        summary = "отказов применения: 1",
        sections = sections
    )

    @Test
    fun `report lands under maxvibes reports psi`() {
        val path = PsiFailureReportWriter(projectRoot.absolutePath).report(report())

        assertNotNull(path)
        val file = File(path!!)
        assertTrue(file.exists())
        assertEquals(
            File(projectRoot, ".maxvibes/reports/psi").absolutePath,
            file.parentFile.absolutePath
        )
    }

    @Test
    fun `file name starts with a timestamp and carries kind and session`() {
        val path = PsiFailureReportWriter(projectRoot.absolutePath)
            .report(report(kind = PsiFailureReport.Kind.PARSE, sessionId = "chat/42"))

        val name = File(path!!).name
        assertTrue(
            name.matches(Regex("\\d{8}-\\d{6}-\\d{3}_parse_chat-42\\.md")),
            "unexpected report file name: $name"
        )
    }

    @Test
    fun `section bodies are written in full`() {
        val body = "x".repeat(20_000)
        val path = PsiFailureReportWriter(projectRoot.absolutePath).report(
            report(sections = listOf(PsiFailureReport.Section("Правка", body)))
        )

        val text = File(path!!).readText()
        assertTrue(text.contains("## Правка"))
        assertTrue(text.contains(body), "report body was truncated")
    }
}
