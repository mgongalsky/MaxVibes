package com.maxvibes.application.service

import com.maxvibes.application.port.output.CheckRunnerPort
import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckIssue
import com.maxvibes.domain.model.check.CheckKind
import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.check.CheckStatus
import com.maxvibes.domain.model.check.IssueSeverity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.maxvibes.domain.model.check.CheckCancellation
import com.maxvibes.domain.model.check.CheckProgressSink

class CheckExecutionServiceTest {

    private class FakeRunner(
        private val supported: Set<CheckKind> = setOf(CheckKind.BUILD, CheckKind.TESTS),
        private val result: (CheckRequest) -> CheckExecution = { CheckExecution(it, CheckStatus.PASSED) }
    ) : CheckRunnerPort {
        override fun supports(kind: CheckKind) = kind in supported

        override suspend fun run(
            request: CheckRequest,
            progress: CheckProgressSink,
            cancellation: CheckCancellation
        ) = result(request)
    }

    private fun service(runner: CheckRunnerPort = FakeRunner()) = CheckExecutionService(runner)

    @Test
    fun `unsupported kind never reaches the runner`() = runBlocking {
        val service = service(FakeRunner(supported = setOf(CheckKind.TESTS)))

        val execution = service.run(CheckRequest(CheckKind.BUILD))

        assertEquals(CheckStatus.UNSUPPORTED, execution.status)
        assertTrue(execution.rawOutput.contains("BUILD"))
    }

    @Test
    fun `runner failure becomes an ERROR execution instead of propagating`() = runBlocking {
        val service = service(FakeRunner { throw IllegalStateException("compiler exploded") })

        val execution = service.run(CheckRequest(CheckKind.BUILD))

        assertEquals(CheckStatus.ERROR, execution.status)
        assertEquals("compiler exploded", execution.rawOutput)
    }

    @Test
    fun `green build without issues formats to a single header line`() {
        val formatted = service().formatForLlm(
            CheckExecution(CheckRequest(CheckKind.BUILD), CheckStatus.PASSED)
        )

        assertEquals("=== CHECK BUILD — PASSED ===", formatted)
    }

    @Test
    fun `header carries scope, duration and test counters`() {
        val formatted = service().formatForLlm(
            CheckExecution(
                request = CheckRequest(CheckKind.TESTS, scope = "com.example.OrderTest"),
                status = CheckStatus.FAILED,
                testsTotal = 12,
                testsFailed = 2,
                durationMs = 2_500
            )
        )

        assertEquals(
            "=== CHECK TESTS (com.example.OrderTest) — FAILED in 2s ===\nTests: 12 total, 2 failed",
            formatted
        )
    }

    @Test
    fun `issues render with location, severity and indented details`() {
        val formatted = service().formatForLlm(
            CheckExecution(
                request = CheckRequest(CheckKind.BUILD),
                status = CheckStatus.FAILED,
                issues = listOf(
                    CheckIssue(
                        message = "Unresolved reference: foo",
                        filePath = "src/main/kotlin/A.kt",
                        line = 10
                    ),
                    CheckIssue(
                        message = "Deprecated call",
                        severity = IssueSeverity.WARNING,
                        details = "use bar() instead"
                    )
                )
            )
        )

        assertEquals(
            """
            === CHECK BUILD — FAILED ===
            - src/main/kotlin/A.kt:10 — Unresolved reference: foo
            - [warn] Deprecated call
                use bar() instead
            """.trimIndent(),
            formatted
        )
    }

    @Test
    fun `issue list is capped and the remainder is counted`() {
        val issues = (1..5).map { CheckIssue(message = "error $it") }

        val formatted = service().formatForLlm(
            CheckExecution(CheckRequest(CheckKind.BUILD), CheckStatus.FAILED, issues = issues),
            maxIssues = 2
        )

        assertEquals(
            "=== CHECK BUILD — FAILED ===\n- error 1\n- error 2\n...and 3 more",
            formatted
        )
    }

    @Test
    fun `raw output is a fallback used only when nothing was parsed`() {
        val withIssues = service().formatForLlm(
            CheckExecution(
                request = CheckRequest(CheckKind.BUILD),
                status = CheckStatus.FAILED,
                issues = listOf(CheckIssue(message = "parsed")),
                rawOutput = "raw log tail"
            )
        )
        val withoutIssues = service().formatForLlm(
            CheckExecution(
                request = CheckRequest(CheckKind.BUILD),
                status = CheckStatus.ERROR,
                rawOutput = "raw log tail"
            )
        )

        assertTrue(!withIssues.contains("raw log tail"))
        assertTrue(withoutIssues.endsWith("raw log tail"))
    }

    @Test
    fun `decline comment reaches the agent`() {
        val formatted = service().formatForLlm(
            CheckExecution(
                request = CheckRequest(CheckKind.TESTS),
                status = CheckStatus.DECLINED,
                declineComment = "not now, fix the build first"
            )
        )

        assertEquals(
            "=== CHECK TESTS — DECLINED ===\nUser comment: not now, fix the build first",
            formatted
        )
    }

    @Test
    fun `a failure after cancellation is reported as CANCELLED, not as an error`() = runBlocking {
        val cancellation = CheckCancellation()
        val service = service(FakeRunner {
            cancellation.cancel()
            throw IllegalStateException("process destroyed")
        })

        val execution = service.run(CheckRequest(CheckKind.BUILD), CheckProgressSink.NOOP, cancellation)

        assertEquals(CheckStatus.CANCELLED, execution.status)
    }
}
