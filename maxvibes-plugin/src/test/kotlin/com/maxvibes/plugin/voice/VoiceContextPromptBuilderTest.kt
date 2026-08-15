package com.maxvibes.plugin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceContextPromptBuilderTest {
    @Test
    fun includesProjectDefaultsAndGlossaryWithoutCaseInsensitiveDuplicates() {
        val terms = VoiceContextPromptBuilder.build(
            projectName = "MaxVibes",
            glossaryTerms = listOf("Kotlin", "ChatInputPanel", " chatinputpanel ", "")
        )

        assertEquals(1, terms.count { it.equals("MaxVibes", ignoreCase = true) })
        assertEquals(1, terms.count { it.equals("Kotlin", ignoreCase = true) })
        assertEquals(1, terms.count { it.equals("ChatInputPanel", ignoreCase = true) })
        assertTrue("IntelliJ IDEA" in terms)
        assertTrue("PSI" in terms)
        assertTrue("Codex" in terms)
        assertTrue("Claude Code" in terms)
    }
}
