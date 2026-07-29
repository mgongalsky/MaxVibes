package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SendPreparationPolicyTest {

    private fun image(id: String) = AttachedImage(
        mediaType = "image/png",
        base64Data = id
    )

    private fun pending(
        trace: String? = null,
        errors: String? = null,
        images: List<AttachedImage> = emptyList(),
        oneShot: PendingOneShot? = null
    ) = PendingTurnSnapshot(
        trace = trace,
        errors = errors,
        images = images,
        oneShot = oneShot
    )

    @Test
    fun `session prompt is preserved when no one-shot is armed`() {
        val prepared = SendPreparationPolicy.prepare(
            pending = pending(trace = "trace", errors = "errors"),
            selectedSpecificPromptName = "session-skill",
            mode = InteractionMode.CLIPBOARD
        )

        assertEquals("session-skill", prepared.effectivePromptName)
        assertEquals("trace", prepared.effectiveTrace)
        assertEquals("errors", prepared.errors)
        assertTrue(prepared.warnings.isEmpty())
    }

    @Test
    fun `one-shot overrides session prompt and prepends element context to trace`() {
        val prepared = SendPreparationPolicy.prepare(
            pending = pending(
                trace = "trace",
                oneShot = PendingOneShot(
                    skillName = "one-shot-skill",
                    elementContext = "class Example",
                    label = "Write test"
                )
            ),
            selectedSpecificPromptName = "session-skill",
            mode = InteractionMode.CLAUDE_CODE
        )

        assertEquals("one-shot-skill", prepared.effectivePromptName)
        assertEquals(
            "class Example" + System.lineSeparator() + System.lineSeparator() + "trace",
            prepared.effectiveTrace
        )
        assertEquals("Write test", prepared.oneShotLabel)
    }

    @Test
    fun `Claude Code keeps attached images without warning`() {
        val attached = image("first")

        val prepared = SendPreparationPolicy.prepare(
            pending = pending(images = listOf(attached)),
            selectedSpecificPromptName = null,
            mode = InteractionMode.CLAUDE_CODE
        )

        assertEquals(listOf(attached), prepared.images)
        assertTrue(prepared.warnings.isEmpty())
    }

    @Test
    fun `API warns that attached images are dropped`() {
        val prepared = SendPreparationPolicy.prepare(
            pending = pending(images = listOf(image("first"), image("second"))),
            selectedSpecificPromptName = null,
            mode = InteractionMode.API
        )

        assertEquals(1, prepared.warnings.size)
        assertTrue(prepared.warnings.single().contains("2 image(s) dropped"))
    }

    @Test
    fun `API emits prefill-only warning for one-shot skill`() {
        val prepared = SendPreparationPolicy.prepare(
            pending = pending(
                oneShot = PendingOneShot("skill", "context", "label")
            ),
            selectedSpecificPromptName = null,
            mode = InteractionMode.API
        )

        assertEquals(1, prepared.warnings.size)
        assertTrue(prepared.warnings.single().contains("API mode gets the prefill text only"))
    }

    @Test
    fun `Cheap API emits missing skill-body warning`() {
        val prepared = SendPreparationPolicy.prepare(
            pending = pending(
                oneShot = PendingOneShot("skill", "context", "label")
            ),
            selectedSpecificPromptName = null,
            mode = InteractionMode.CHEAP_API
        )

        assertEquals(1, prepared.warnings.size)
        assertTrue(prepared.warnings.single().contains("Cheap API gets the element context but not the skill body"))
    }

    @Test
    fun `Clipboard accepts one-shot without warning`() {
        val prepared = SendPreparationPolicy.prepare(
            pending = pending(
                oneShot = PendingOneShot("skill", "context", "label")
            ),
            selectedSpecificPromptName = "session-skill",
            mode = InteractionMode.CLIPBOARD
        )

        assertEquals("skill", prepared.effectivePromptName)
        assertEquals("context", prepared.effectiveTrace)
        assertTrue(prepared.warnings.isEmpty())
    }
}
