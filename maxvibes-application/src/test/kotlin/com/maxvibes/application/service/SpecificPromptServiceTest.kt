package com.maxvibes.application.service

import com.maxvibes.application.port.output.SpecificPromptRepository
import com.maxvibes.domain.model.interaction.SpecificPrompt
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpecificPromptServiceTest {

    private val repository: SpecificPromptRepository = mockk()
    private val service = SpecificPromptService(repository)

    @Test
    fun `getAvailablePromptNames returns names of all prompts`() {
        every { repository.loadAll() } returns listOf(
            SpecificPrompt("Analyze Only", "content1"),
            SpecificPrompt("Refactor(Feathers)- Extract & Override", "content2")
        )
        val names = service.getAvailablePromptNames()
        assertEquals(listOf("Analyze Only", "Refactor(Feathers)- Extract & Override"), names)
    }

    @Test
    fun `getAvailablePromptNames returns empty list when directory missing`() {
        every { repository.loadAll() } returns emptyList()
        assertTrue(service.getAvailablePromptNames().isEmpty())
    }

    @Test
    fun `resolvePromptContent returns null for null name (Just Code)`() {
        assertNull(service.resolvePromptContent(null))
    }

    @Test
    fun `resolvePromptContent returns content when prompt exists`() {
        every { repository.loadByName("Analyze Only") } returns
                SpecificPrompt("Analyze Only", "Do not modify code.")
        assertEquals("Do not modify code.", service.resolvePromptContent("Analyze Only"))
    }

    @Test
    fun `resolvePromptContent returns null when prompt not found`() {
        every { repository.loadByName("Missing") } returns null
        assertNull(service.resolvePromptContent("Missing"))
    }

    @Test
    fun `validatePromptName returns null for null`() {
        assertNull(service.validatePromptName(null))
    }

    @Test
    fun `validatePromptName returns name when file exists`() {
        every { repository.loadByName("Analyze Only") } returns
                SpecificPrompt("Analyze Only", "content")
        assertEquals("Analyze Only", service.validatePromptName("Analyze Only"))
    }

    @Test
    fun `validatePromptName returns null when file no longer exists`() {
        every { repository.loadByName("Deleted Prompt") } returns null
        assertNull(service.validatePromptName("Deleted Prompt"))
    }
}
