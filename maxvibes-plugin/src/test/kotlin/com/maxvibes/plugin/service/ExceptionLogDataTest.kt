package com.maxvibes.plugin.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExceptionLogDataTest {
    @Test
    fun `preserves deep frames causes and suppressed exceptions`() {
        val cause = IllegalArgumentException("root cause")
        val failure = IllegalStateException("outer failure", cause)
        failure.stackTrace = Array(24) { index -> StackTraceElement("Example", "frame$index", "Example.kt", index + 1) }
        failure.addSuppressed(UnsupportedOperationException("suppressed detail"))
        val data = exceptionLogData(failure, mapOf("operation" to "replace"))!!
        val stack = data["stack"] as String
        assertTrue(stack.contains("frame23"))
        assertTrue(stack.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
        assertTrue(stack.contains("Suppressed: java.lang.UnsupportedOperationException: suppressed detail"))
        assertEquals("replace", data["operation"])
        assertEquals("IllegalStateException", data["ex"])
        assertEquals("outer failure", data["exMsg"])
    }

    @Test
    fun `cyclic causes terminate with standard circular reference marker`() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        assertTrue((exceptionLogData(first, null)!!["stack"] as String).contains("CIRCULAR REFERENCE"))
    }

    @Test
    fun `no exception preserves existing data`() {
        val base = mapOf<String, Any?>("operation" to "replace")
        assertSame(base, exceptionLogData(null, base))
        assertNull(exceptionLogData(null, null))
    }
}
