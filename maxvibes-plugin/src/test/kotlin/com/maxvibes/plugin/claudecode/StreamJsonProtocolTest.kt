package com.maxvibes.plugin.claudecode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for [StreamJsonProtocol.extractThinkingFull] (ThinkingBubble feature),
 * plus a regression guard that thinking never leaks into
 * [StreamJsonProtocol.extractAssistantText].
 *
 * Lines mimic the claude-code 2.1.x stream-json shape: assistant events carry
 * message.content[] blocks with a `type` discriminator.
 *
 * Run via the IntelliJ IDEA test runner (plugin-module tests are not run through Gradle).
 */
class StreamJsonProtocolTest {

    // ── extractThinkingFull: positive ──────────────────────────────────────

    @Test
    fun `single thinking block returns full text with newlines preserved`() {
        val line =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Line one.\nLine two.\n\nParagraph two.","signature":"abc"}]}}"""
        assertEquals(
            "Line one.\nLine two.\n\nParagraph two.",
            StreamJsonProtocol.extractThinkingFull(line)
        )
    }

    @Test
    fun `long thinking is not truncated`() {
        // ~520 chars — far beyond the 90-char cap of extractThinkingPreview.
        val longText = "deep thought ".repeat(40).trim()
        val line =
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"$longText"}]}}"""
        assertEquals(longText, StreamJsonProtocol.extractThinkingFull(line))
    }

    @Test
    fun `two thinking blocks in one event are joined with a blank line`() {
        val line =
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"alpha"},{"type":"thinking","thinking":"beta"}]}}"""
        assertEquals("alpha\n\nbeta", StreamJsonProtocol.extractThinkingFull(line))
    }

    @Test
    fun `mixed content returns only thinking text`() {
        val line =
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"inner monologue"},{"type":"text","text":"final answer"}]}}"""
        assertEquals("inner monologue", StreamJsonProtocol.extractThinkingFull(line))
    }

    // ── extractThinkingFull: negative ──────────────────────────────────────

    @Test
    fun `text-only assistant event yields null`() {
        val line =
            """{"type":"assistant","message":{"content":[{"type":"text","text":"just the answer"}]}}"""
        assertNull(StreamJsonProtocol.extractThinkingFull(line))
    }

    @Test
    fun `blank thinking yields null`() {
        val line =
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"   "}]}}"""
        assertNull(StreamJsonProtocol.extractThinkingFull(line))
    }

    @Test
    fun `non-assistant events yield null`() {
        val init = """{"type":"system","subtype":"init","session_id":"s-1"}"""
        val result = """{"type":"result","is_error":false,"result":"ok"}"""
        assertNull(StreamJsonProtocol.extractThinkingFull(init))
        assertNull(StreamJsonProtocol.extractThinkingFull(result))
    }

    @Test
    fun `malformed line yields null`() {
        assertNull(StreamJsonProtocol.extractThinkingFull("not json at all {{{"))
    }

    // ── regression: thinking never leaks into assistant text ───────────────

    @Test
    fun `extractAssistantText ignores thinking blocks`() {
        val thinkingOnly =
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"hidden"}]}}"""
        val mixed =
            """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"visible"}]}}"""
        assertNull(StreamJsonProtocol.extractAssistantText(thinkingOnly))
        assertEquals("visible", StreamJsonProtocol.extractAssistantText(mixed))
    }
}
