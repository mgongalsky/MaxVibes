package com.maxvibes.plugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskContextFormatterTest {
    @Test
    fun `build returns task unchanged without attachments`() {
        assertEquals("task", TaskContextFormatter.build("task", null, null))
    }

    @Test
    fun `build appends trace and errors using platform separators`() {
        val separator = System.lineSeparator()

        val result = TaskContextFormatter.build(
            task = "task",
            trace = "trace",
            errors = "errors"
        )

        assertEquals(
            "task" + separator + separator +
                    "--- Error/Trace/Logs ---" + separator + "trace" +
                    separator + separator +
                    "--- IDE Errors ---" + separator + "errors",
            result
        )
    }

    @Test
    fun `build ignores blank attachments`() {
        assertEquals("task", TaskContextFormatter.build("task", "  ", ""))
    }
}
