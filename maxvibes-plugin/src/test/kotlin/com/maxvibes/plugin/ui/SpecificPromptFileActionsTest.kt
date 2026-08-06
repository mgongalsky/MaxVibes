package com.maxvibes.plugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SpecificPromptFileActionsTest {

    @TempDir
    lateinit var base: File

    @Test
    fun `create opens the new file reports status and refreshes`() {
        val fixture = Fixture(base)

        fixture.actions().create()

        assertEquals("new_prompt.md", fixture.opened.single().name)
        assertEquals(
            listOf("Created: new_prompt.md — add your prompt text and save"),
            fixture.statuses
        )
        assertEquals(1, fixture.refreshCount)
    }

    @Test
    fun `create reports filesystem failure without refreshing`() {
        val promptDirectory = File(base, ".maxvibes/prompts/specific")
        promptDirectory.parentFile.mkdirs()
        promptDirectory.writeText("not a directory")
        val fixture = Fixture(base)

        fixture.actions().create()

        assertTrue(fixture.statuses.single().startsWith("Failed to create prompt file:"))
        assertTrue(fixture.opened.isEmpty())
        assertEquals(0, fixture.refreshCount)
    }

    @Test
    fun `edit opens the currently selected prompt`() {
        val fixture = Fixture(base)
        val prompt = fixture.givenPrompt("review.md")
        fixture.selected = "review"

        fixture.actions().edit()

        assertEquals(listOf(prompt), fixture.opened)
        assertTrue(fixture.statuses.isEmpty())
        assertEquals(0, fixture.refreshCount)
    }

    @Test
    fun `edit does nothing without a selected prompt`() {
        val fixture = Fixture(base)

        fixture.actions().edit()

        assertTrue(fixture.opened.isEmpty())
    }

    @Test
    fun `cancelled delete leaves the prompt untouched`() {
        val fixture = Fixture(base)
        val prompt = fixture.givenPrompt("review.md")
        fixture.selected = "review"

        fixture.actions(confirmDelete = { false }).delete()

        assertTrue(prompt.exists())
        assertTrue(fixture.statuses.isEmpty())
        assertEquals(0, fixture.refreshCount)
    }

    @Test
    fun `delete removes prompt clears persisted selection and refreshes`() {
        val fixture = Fixture(base)
        val prompt = fixture.givenPrompt("review.md")
        fixture.selected = "review"
        fixture.persisted = "review"

        fixture.actions().delete()

        assertFalse(prompt.exists())
        assertEquals(1, fixture.clearSelectionCount)
        assertEquals(listOf("Deleted prompt: review"), fixture.statuses)
        assertEquals(1, fixture.refreshCount)
    }

    @Test
    fun `delete preserves another persisted selection`() {
        val fixture = Fixture(base)
        fixture.givenPrompt("review.md")
        fixture.selected = "review"
        fixture.persisted = "another"

        fixture.actions().delete()

        assertEquals(0, fixture.clearSelectionCount)
    }

    @Test
    fun `delete reports missing file without refreshing`() {
        val fixture = Fixture(base)
        fixture.selected = "missing"

        fixture.actions().delete()

        assertEquals(listOf("Failed to delete prompt file"), fixture.statuses)
        assertEquals(0, fixture.refreshCount)
    }

    private class Fixture(base: File) {
        private val files = SpecificPromptFiles(base.absolutePath)
        private val promptDirectory = File(base, ".maxvibes/prompts/specific")

        var selected: String? = null
        var persisted: String? = null
        val opened = mutableListOf<File>()
        val statuses = mutableListOf<String>()
        var clearSelectionCount = 0
        var refreshCount = 0

        fun givenPrompt(fileName: String): File = File(promptDirectory, fileName).also {
            it.parentFile.mkdirs()
            it.writeText("content")
        }

        fun actions(
            confirmDelete: (String) -> Boolean = { true }
        ): SpecificPromptFileActions = SpecificPromptFileActions(
            files = files,
            selectedPromptName = { selected },
            persistedPromptName = { persisted },
            openFile = { opened += it },
            confirmDelete = confirmDelete,
            onClearSelection = { clearSelectionCount++ },
            onStatus = { statuses += it },
            onRefresh = { refreshCount++ }
        )
    }
}
