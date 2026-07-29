package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionMode

internal data class PreparedSend(
    val effectivePromptName: String?,
    val effectiveTrace: String?,
    val errors: String?,
    val images: List<AttachedImage>,
    val warnings: List<String>,
    val oneShotLabel: String?
)

/**
 * Pure policy that transforms pending one-turn state into mode-ready send data.
 *
 * It contains no UI, IntelliJ or dispatcher dependencies. The caller remains
 * responsible for consuming the pending context and displaying the warnings.
 */
internal object SendPreparationPolicy {

    fun prepare(
        pending: PendingTurnSnapshot,
        selectedSpecificPromptName: String?,
        mode: InteractionMode
    ): PreparedSend {
        val effectivePromptName = pending.oneShot?.skillName ?: selectedSpecificPromptName
        val effectiveTrace = listOfNotNull(
            pending.oneShot?.elementContext,
            pending.trace
        ).takeIf { it.isNotEmpty() }?.joinToString(
            separator = System.lineSeparator() + System.lineSeparator()
        )

        return PreparedSend(
            effectivePromptName = effectivePromptName,
            effectiveTrace = effectiveTrace,
            errors = pending.errors,
            images = pending.images,
            warnings = buildWarnings(pending, mode),
            oneShotLabel = pending.oneShot?.label
        )
    }

    private fun buildWarnings(
        pending: PendingTurnSnapshot,
        mode: InteractionMode
    ): List<String> = buildList {
        if (pending.images.isNotEmpty() && mode != InteractionMode.CLAUDE_CODE) {
            add(
                "⚠️ ${pending.images.size} image(s) dropped — images are only sent in Claude Code mode"
            )
        }

        if (pending.oneShot != null) {
            when (mode) {
                InteractionMode.API -> add(
                    "⚠️ One-shot editor skill fully works only in Clipboard / Claude Code modes — " +
                            "API mode gets the prefill text only"
                )

                InteractionMode.CHEAP_API -> add(
                    "⚠️ One-shot editor skill fully works only in Clipboard / Claude Code modes — " +
                            "Cheap API gets the element context but not the skill body"
                )

                InteractionMode.CLIPBOARD,
                InteractionMode.CLAUDE_CODE -> Unit
            }
        }
    }
}
