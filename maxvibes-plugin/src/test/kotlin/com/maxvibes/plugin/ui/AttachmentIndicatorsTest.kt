package com.maxvibes.plugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttachmentIndicatorsTest {

    private fun describe(trace: String? = null, errors: String? = null, hasImages: Boolean = false) =
        AttachmentIndicators.describe(trace, errors, hasImages)

    @Test
    fun `nothing attached hides both labels and the bar`() {
        val state = describe()

        assertFalse(state.traceVisible)
        assertFalse(state.errorsVisible)
        assertFalse(state.barVisible)
    }

    @Test
    fun `nothing attached leaves both captions untouched`() {
        val state = describe()

        assertNull(state.traceText)
        assertNull(state.errorsText)
    }

    @Test
    fun `a blank trace counts as no trace`() {
        val state = describe(trace = "   \n  ")

        assertFalse(state.traceVisible)
        assertNull(state.traceText)
    }

    @Test
    fun `a single line trace reports one line`() {
        val state = describe(trace = "boom")

        assertTrue(state.traceVisible)
        assertEquals("\uD83D\uDCCE Trace: 1L", state.traceText)
    }

    @Test
    fun `a multi line trace reports its line count`() {
        val state = describe(trace = "a\nb\nc")

        assertEquals("\uD83D\uDCCE Trace: 3L", state.traceText)
    }

    @Test
    fun `a trailing newline counts as an extra trace line`() {
        val state = describe(trace = "a\nb\n")

        assertEquals("\uD83D\uDCCE Trace: 3L", state.traceText)
    }

    @Test
    fun `a blank errors text counts as no errors`() {
        val state = describe(errors = "  ")

        assertFalse(state.errorsVisible)
        assertNull(state.errorsText)
    }

    @Test
    fun `errors are counted by their file block markers`() {
        val state = describe(errors = "File: A.kt\nboom\nFile: B.kt\nbang")

        assertTrue(state.errorsVisible)
        assertEquals("\uD83D\uDC1E Errors: 2", state.errorsText)
    }

    @Test
    fun `errors without any marker report zero`() {
        val state = describe(errors = "something went wrong")

        assertTrue(state.errorsVisible)
        assertEquals("\uD83D\uDC1E Errors: 0", state.errorsText)
    }

    @Test
    fun `a marker inside the error text inflates the count`() {
        val state = describe(errors = "File: A.kt\ncannot resolve File: B.kt")

        assertEquals("\uD83D\uDC1E Errors: 2", state.errorsText)
    }

    @Test
    fun `the bar is shown for a trace alone`() {
        assertTrue(describe(trace = "boom").barVisible)
    }

    @Test
    fun `the bar is shown for errors alone`() {
        assertTrue(describe(errors = "File: A.kt").barVisible)
    }

    @Test
    fun `the bar is shown for images alone`() {
        assertTrue(describe(hasImages = true).barVisible)
    }

    @Test
    fun `the labels stay hidden when only images are attached`() {
        val state = describe(hasImages = true)

        assertFalse(state.traceVisible)
        assertFalse(state.errorsVisible)
    }
}
