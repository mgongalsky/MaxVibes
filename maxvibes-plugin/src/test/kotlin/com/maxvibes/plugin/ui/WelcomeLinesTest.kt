package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.InteractionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WelcomeLinesTest {

    private fun build(
        mode: InteractionMode = InteractionMode.API,
        isBranch: Boolean = false,
        parentTitle: String? = null,
        contextFilesCount: Int = 0
    ) = WelcomeLines.build(mode, isBranch, parentTitle, contextFilesCount)

    @Test
    fun `a plain root session gets a header and a hint`() {
        val lines = build()

        assertEquals(2, lines.size)
        assertEquals("MaxVibes  \u2022  API \u2014 direct LLM calls", lines.first())
        assertEquals("Type your task \u2022 Ctrl+Enter to send", lines.last())
    }

    @Test
    fun `each mode gets its own caption`() {
        assertTrue(
            build(mode = InteractionMode.CLIPBOARD).first().endsWith("Clipboard \u2014 paste JSON into Claude/ChatGPT")
        )
        assertTrue(build(mode = InteractionMode.CHEAP_API).first().endsWith("Cheap API \u2014 budget model"))
        assertTrue(build(mode = InteractionMode.CLAUDE_CODE).first().endsWith("Claude Code \u2014 local CLI process"))
    }

    @Test
    fun `a resolved parent title is rendered without a closing quote`() {
        val lines = build(isBranch = true, parentTitle = "Feature X")

        assertEquals("\u2514 Branch from: \"Feature X", lines[1])
    }

    @Test
    fun `a missing parent title falls back to a quoted question mark`() {
        val lines = build(isBranch = true, parentTitle = null)

        assertEquals("\u2514 Branch from: \"?\"", lines[1])
    }

    @Test
    fun `a root session mentions no branch`() {
        val lines = build(isBranch = false, parentTitle = "Feature X")

        assertFalse(lines.any { it.contains("Branch from") })
    }

    @Test
    fun `context files are announced when present`() {
        val lines = build(contextFilesCount = 3)

        assertEquals("\uD83D\uDCCE 3 global context file(s) active", lines[1])
    }

    @Test
    fun `no context files means no context line`() {
        val lines = build(contextFilesCount = 0)

        assertFalse(lines.any { it.contains("global context") })
    }

    @Test
    fun `branch and context lines keep their order between header and hint`() {
        val lines = build(isBranch = true, parentTitle = "Parent", contextFilesCount = 2)

        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("MaxVibes"))
        assertTrue(lines[1].startsWith("\u2514 Branch from"))
        assertTrue(lines[2].contains("global context"))
        assertTrue(lines[3].startsWith("Type your task"))
    }
}
