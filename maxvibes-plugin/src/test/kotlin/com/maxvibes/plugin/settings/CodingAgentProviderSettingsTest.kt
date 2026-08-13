package com.maxvibes.plugin.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodingAgentProviderSettingsTest {
    @Test
    fun `legacy installs default coding agent provider to Claude Code`() {
        val settings = MaxVibesSettings()

        assertEquals("CLAUDE_CODE", settings.codingAgentProvider)
    }

    @Test
    fun `provider options expose Claude Code and Codex`() {
        assertEquals(
            listOf(
                "CLAUDE_CODE" to "Claude Code",
                "CODEX" to "Codex"
            ),
            MaxVibesSettings.CODING_AGENT_PROVIDERS
        )
        assertTrue(MaxVibesSettings.INTERACTION_MODES.any { it.first == "CLAUDE_CODE" })
    }
}
