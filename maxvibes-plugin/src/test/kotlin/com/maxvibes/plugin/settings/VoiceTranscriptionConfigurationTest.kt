package com.maxvibes.plugin.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceTranscriptionConfigurationTest {
    @Test
    fun `configuration requires key endpoint and model`() {
        assertTrue(VoiceTranscriptionConfiguration(apiKey = "secret").isConfigured)
        assertFalse(VoiceTranscriptionConfiguration(apiKey = " ").isConfigured)
        assertFalse(
            VoiceTranscriptionConfiguration(apiKey = "secret", endpoint = " ").isConfigured
        )
        assertFalse(
            VoiceTranscriptionConfiguration(apiKey = "secret", model = " ").isConfigured
        )
    }

    @Test
    fun `glossary accepts commas and lines and removes blanks`() {
        val configuration = VoiceTranscriptionConfiguration(
            glossary = "Kotlin, IntelliJ IDEA\n\nPSI\rClaude Code,  "
        )

        assertEquals(
            listOf("Kotlin", "IntelliJ IDEA", "PSI", "Claude Code"),
            configuration.glossaryTerms()
        )
    }
}
