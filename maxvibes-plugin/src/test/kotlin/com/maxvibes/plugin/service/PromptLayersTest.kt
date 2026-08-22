package com.maxvibes.plugin.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Базовый промпт приходит из плагина, слой проекта лежит поверх, и ни один из них
 * не может незаметно подменить другой.
 */
class PromptLayersTest {

    @TempDir
    lateinit var promptsDir: File

    private fun layers(base: (PromptKind) -> String = { "BASE ${it.fileName}" }) =
        PromptLayers(promptsDir, base)

    @Test
    fun `without a project layer the prompt is the plugin text verbatim`() {
        assertEquals("BASE codex-system.md", layers().compose(PromptKind.CODEX_SYSTEM))
    }

    @Test
    fun `a project layer is appended and announced as taking priority`() {
        File(promptsDir, PromptKind.CODEX_SYSTEM.localFileName).writeText("Always answer in Russian.")

        val composed = layers().compose(PromptKind.CODEX_SYSTEM)

        assertTrue(composed.startsWith("BASE codex-system.md"), composed)
        assertTrue(composed.contains("take priority"), composed)
        assertTrue(composed.trimEnd().endsWith("Always answer in Russian."), composed)
    }

    @Test
    fun `an untouched template layer never reaches the model`() {
        layers().sync()

        assertFalse(layers().hasOverlay(PromptKind.CODEX_SYSTEM))
        assertEquals("BASE codex-system.md", layers().compose(PromptKind.CODEX_SYSTEM))
    }

    @Test
    fun `sync mirrors every base prompt and seeds every layer`() {
        val report = layers().sync()

        assertEquals(PromptKind.values().size, report.baseWritten.size)
        assertEquals(PromptKind.values().size, report.localCreated.size)
        PromptKind.values().forEach { kind ->
            assertTrue(
                File(promptsDir, "${PromptLayers.BASE_DIR}/${kind.fileName}").isFile,
                kind.fileName
            )
            assertTrue(File(promptsDir, kind.localFileName).isFile, kind.localFileName)
        }
    }

    @Test
    fun `a second sync changes nothing`() {
        layers().sync()

        assertTrue(layers().sync().isEmpty())
    }

    @Test
    fun `sync never touches an existing layer`() {
        val local = File(promptsDir, PromptKind.CHAT_SYSTEM.localFileName)
        local.writeText("my own rules")

        layers().sync()

        assertEquals("my own rules", local.readText())
    }

    @Test
    fun `sync refreshes the mirror when the plugin text changes`() {
        layers { "old text" }.sync()

        val report = layers { "new text" }.sync()

        assertEquals(PromptKind.values().size, report.baseWritten.size)
        assertTrue(report.localCreated.isEmpty())
        assertTrue(
            File(promptsDir, "${PromptLayers.BASE_DIR}/${PromptKind.CHAT_SYSTEM.fileName}")
                .readText().contains("new text")
        )
    }

    @Test
    fun `legacy prompts are detected but left alone until asked`() {
        File(promptsDir, PromptKind.CHAT_SYSTEM.fileName).writeText("an old hand-written override")

        assertEquals(1, layers().legacyFiles().size)
        assertTrue(layers().sync().legacyArchived.isEmpty())
        assertTrue(File(promptsDir, PromptKind.CHAT_SYSTEM.fileName).isFile)
    }

    @Test
    fun `archiving a legacy prompt keeps its content`() {
        File(promptsDir, PromptKind.CHAT_SYSTEM.fileName).writeText("an old hand-written override")

        val report = layers().sync(archiveLegacy = true)

        assertEquals(listOf("chat-system.legacy.md"), report.legacyArchived)
        assertFalse(File(promptsDir, PromptKind.CHAT_SYSTEM.fileName).exists())
        assertEquals(
            "an old hand-written override",
            File(promptsDir, "chat-system.legacy.md").readText()
        )
    }

    @Test
    fun `a taken archive name does not silently swallow the file`() {
        File(promptsDir, "chat-system.legacy.md").writeText("previous archive")
        File(promptsDir, PromptKind.CHAT_SYSTEM.fileName).writeText("fresh override")

        val report = layers().sync(archiveLegacy = true)

        assertEquals(listOf("chat-system.legacy.2.md"), report.legacyArchived)
        assertEquals("previous archive", File(promptsDir, "chat-system.legacy.md").readText())
        assertEquals("fresh override", File(promptsDir, "chat-system.legacy.2.md").readText())
    }
}
