package com.maxvibes.application.service

import com.maxvibes.application.port.output.SpecificPromptRepository
import com.maxvibes.domain.model.interaction.PromptSource
import com.maxvibes.domain.model.interaction.SkillEditorSpec
import com.maxvibes.domain.model.interaction.SpecificPrompt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecificPromptServiceEditorTest {

    private class FakeRepo(private val prompts: List<SpecificPrompt>) : SpecificPromptRepository {
        override fun loadAll(): List<SpecificPrompt> = prompts
        override fun loadByName(name: String): SpecificPrompt? = prompts.firstOrNull { it.name == name }
    }

    private fun skill(name: String, spec: SkillEditorSpec?) = SpecificPrompt(
        name = name,
        content = "body",
        description = "",
        filePath = null,
        source = PromptSource.PROJECT_SKILL,
        editorSpec = spec
    )

    @Test
    fun `filters by kind and any, chat-only skills excluded`() {
        val service = SpecificPromptService(
            FakeRepo(
                listOf(
                    skill("fn-only", SkillEditorSpec(setOf("function"))),
                    skill("cls-only", SkillEditorSpec(setOf("class"))),
                    skill("universal", SkillEditorSpec(setOf("any"))),
                    skill("chat-only", null)
                )
            )
        )
        assertEquals(listOf("fn-only", "universal"), service.editorSkillsFor("function").map { it.name })
        assertEquals(listOf("cls-only", "universal"), service.editorSkillsFor("class").map { it.name })
        assertTrue(service.editorSkillsFor(null).isEmpty())
    }

    @Test
    fun `renders custom template and default fallback`() {
        val custom = skill(
            "s",
            SkillEditorSpec(setOf("function"), template = "Do {{elementName}} at {{elementPath}} in {{filePath}}")
        )
        val service = SpecificPromptService(FakeRepo(listOf(custom)))
        assertEquals(
            "Do bar at file:a/B.kt/class[B]/function[bar] in a/B.kt",
            service.renderEditorTemplate(custom, "file:a/B.kt/class[B]/function[bar]", "bar", "a/B.kt")
        )
        val noTemplate = skill("plain", SkillEditorSpec(setOf("any")))
        assertEquals(
            "Apply the 'plain' skill to X.",
            service.renderEditorTemplate(noTemplate, "X", "n", "f")
        )
    }
}
