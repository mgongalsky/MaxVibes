package com.maxvibes.domain.model.check

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TestFailureTextTest {

    private val stack = listOf(
        "org.opentest4j.AssertionFailedError: expected: <3> but was: <2>",
        "\tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:531)",
        "\tat com.example.broken.BrokenTest.failingTest(BrokenTest.kt:14)",
        "\tat java.base/java.lang.reflect.Method.invoke(Method.java:580)"
    ).joinToString("\n")

    @Test
    fun `comparison hidden by the IDE is appended to the exception header`() {
        assertEquals(
            "org.opentest4j.AssertionFailedError: expected: <3> but was: <2>",
            TestFailureText.message("org.opentest4j.AssertionFailedError:", "3", "2")
        )
    }

    @Test
    fun `a header that already carries the comparison is not repeated`() {
        assertEquals(
            "expected: <3> but was: <2>",
            TestFailureText.message("expected: <3> but was: <2>", "3", "2")
        )
    }

    @Test
    fun `a failure without comparison keeps its own message`() {
        assertEquals("Boom", TestFailureText.message("Boom", null, null))
    }

    @Test
    fun `a silent failure gets a fixed phrase`() {
        assertEquals("Test failed", TestFailureText.message("  ", null, null))
    }

    @Test
    fun `framework frames are dropped, the header and project frames survive`() {
        assertEquals(
            "org.opentest4j.AssertionFailedError: expected: <3> but was: <2>\n" +
                    "at com.example.broken.BrokenTest.failingTest(BrokenTest.kt:14)",
            TestFailureText.relevantFrames(stack)
        )
    }

    @Test
    fun `a stack of nothing but framework frames is kept as is`() {
        val noise = listOf(
            "\tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:531)",
            "\tat java.base/java.lang.reflect.Method.invoke(Method.java:580)"
        ).joinToString("\n")
        assertEquals(
            "at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:531)\n" +
                    "at java.base/java.lang.reflect.Method.invoke(Method.java:580)",
            TestFailureText.relevantFrames(noise)
        )
    }

    @Test
    fun `an absent stack stays absent`() {
        assertNull(TestFailureText.relevantFrames(null))
        assertNull(TestFailureText.relevantFrames("   "))
    }
}
