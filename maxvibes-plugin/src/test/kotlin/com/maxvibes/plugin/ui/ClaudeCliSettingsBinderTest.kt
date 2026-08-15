package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.CodingAgentProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeCliSettingsBinderTest {

    private class FakeSettings(
        override var provider: CodingAgentProvider = CodingAgentProvider.CLAUDE_CODE,
        override var model: String = "",
        override var effortLevel: String = ""
    ) : ClaudeCliSettings

    private val settings = FakeSettings()
    private val statuses = mutableListOf<String>()
    private val binder = ClaudeCliSettingsBinder(settings) { statuses += it }

    private fun shown(sync: ((String) -> Unit) -> Unit): String {
        var value = ""
        sync { value = it }
        return value
    }

    @Test
    fun `sync shows Auto when no model is stored`() {
        assertEquals("Auto", shown(binder::syncModel))
    }

    @Test
    fun `sync shows the stored model`() {
        settings.model = "opus"

        assertEquals("opus", shown(binder::syncModel))
    }

    @Test
    fun `sync shows Auto for a whitespace only model`() {
        settings.model = "   "

        assertEquals("Auto", shown(binder::syncModel))
    }

    @Test
    fun `commit stores a trimmed model and reports it`() {
        binder.commitModel("  sonnet  ")

        assertEquals("sonnet", settings.model)
        assertEquals(listOf("Claude Code model: sonnet \u2014 applies on next send"), statuses)
    }

    @Test
    fun `commit clears the model for Auto regardless of case`() {
        settings.model = "opus"

        binder.commitModel("AUTO")

        assertEquals("", settings.model)
        assertEquals(listOf("Claude Code model: Auto \u2014 applies on next send"), statuses)
    }

    @Test
    fun `commit clears the model for blank input`() {
        settings.model = "opus"

        binder.commitModel("   ")

        assertEquals("", settings.model)
    }

    @Test
    fun `commit ignores a null selection`() {
        settings.model = "opus"

        binder.commitModel(null)

        assertEquals("opus", settings.model)
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `commit stays silent when the model is unchanged`() {
        settings.model = "haiku"

        binder.commitModel("haiku")

        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `a commit triggered during a model sync is ignored`() {
        settings.model = "opus"

        binder.syncModel { binder.commitModel("sonnet") }

        assertEquals("opus", settings.model)
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `the model guard is released after a sync`() {
        binder.syncModel { }

        binder.commitModel("sonnet")

        assertEquals("sonnet", settings.model)
    }

    @Test
    fun `the model guard is released when the sync callback throws`() {
        assertThrows(IllegalStateException::class.java) {
            binder.syncModel { throw IllegalStateException("boom") }
        }

        binder.commitModel("sonnet")

        assertEquals("sonnet", settings.model)
    }

    @Test
    fun `sync shows Auto when no effort is stored`() {
        assertEquals("Auto", shown(binder::syncEffort))
    }

    @Test
    fun `sync shows the stored effort`() {
        settings.effortLevel = "high"

        assertEquals("high", shown(binder::syncEffort))
    }

    @Test
    fun `commit stores the selected effort and reports it`() {
        binder.commitEffort("xhigh")

        assertEquals("xhigh", settings.effortLevel)
        assertEquals(listOf("Claude Code effort: xhigh \u2014 applies on next send"), statuses)
    }

    @Test
    fun `commit clears the effort for Auto`() {
        settings.effortLevel = "low"

        binder.commitEffort("Auto")

        assertEquals("", settings.effortLevel)
        assertEquals(listOf("Claude Code effort: Auto \u2014 applies on next send"), statuses)
    }

    @Test
    fun `commit ignores a non string effort selection`() {
        settings.effortLevel = "low"

        binder.commitEffort(42)

        assertEquals("low", settings.effortLevel)
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `commit stays silent when the effort is unchanged`() {
        settings.effortLevel = "medium"

        binder.commitEffort("medium")

        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `a commit triggered during an effort sync is ignored`() {
        settings.effortLevel = "low"

        binder.syncEffort { binder.commitEffort("max") }

        assertEquals("low", settings.effortLevel)
    }

    @Test
    fun `the two guards are independent`() {
        binder.syncModel { binder.commitEffort("high") }

        assertEquals("high", settings.effortLevel)
    }

    @Test
    fun `the status names the selected agent`() {
        settings.provider = CodingAgentProvider.CODEX

        binder.commitModel("gpt-5.6-sol")

        assertEquals(listOf("Codex model: gpt-5.6-sol \u2014 applies on next send"), statuses)
    }

    @Test
    fun `capabilities follow the selected provider`() {
        assertEquals("Claude Code", binder.capabilities.displayName)

        settings.provider = CodingAgentProvider.CODEX

        assertEquals("Codex", binder.capabilities.displayName)
        assertTrue(binder.capabilities.reasoningLevels.contains("minimal"))
    }
}
