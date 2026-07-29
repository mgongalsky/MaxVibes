package com.maxvibes.plugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SpecificPromptFilesTest {

    @TempDir
    lateinit var base: File

    private val files: SpecificPromptFiles get() = SpecificPromptFiles(base.absolutePath)

    private fun promptDir() = File(base, ".maxvibes/prompts/specific")

    private fun givenPrompt(fileName: String): File =
        File(promptDir(), fileName).also {
            it.parentFile.mkdirs()
            it.writeText("content")
        }

    @Test
    fun `resolve finds a markdown prompt`() {
        val expected = givenPrompt("review.md")

        assertEquals(expected, files.resolve("review"))
    }

    @Test
    fun `resolve finds a text prompt`() {
        val expected = givenPrompt("review.txt")

        assertEquals(expected, files.resolve("review"))
    }

    @Test
    fun `resolve prefers markdown when both extensions exist`() {
        val markdown = givenPrompt("review.md")
        givenPrompt("review.txt")

        assertEquals(markdown, files.resolve("review"))
    }

    @Test
    fun `resolve returns null for an unknown name`() {
        assertNull(files.resolve("missing"))
    }

    @Test
    fun `create makes the directory and a default prompt file`() {
        val created = files.create().getOrThrow()

        assertTrue(created.exists())
        assertEquals("new_prompt.md", created.name)
        assertEquals(promptDir(), created.parentFile)
    }

    @Test
    fun `create seeds the file with a heading matching its name`() {
        val created = files.create().getOrThrow()

        assertTrue(created.readText().startsWith("# new_prompt"))
    }

    @Test
    fun `create picks the next free name when the default is taken`() {
        givenPrompt("new_prompt.md")

        assertEquals("new_prompt_1.md", files.create().getOrThrow().name)
    }

    @Test
    fun `create keeps counting past several taken names`() {
        givenPrompt("new_prompt.md")
        givenPrompt("new_prompt_1.md")
        givenPrompt("new_prompt_2.md")

        assertEquals("new_prompt_3.md", files.create().getOrThrow().name)
    }

    @Test
    fun `delete removes an existing prompt`() {
        val file = givenPrompt("review.md")

        assertTrue(files.delete("review"))
        assertFalse(file.exists())
    }

    @Test
    fun `delete reports failure for an unknown name`() {
        assertFalse(files.delete("missing"))
    }
}
