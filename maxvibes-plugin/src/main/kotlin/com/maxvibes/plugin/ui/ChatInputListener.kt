package com.maxvibes.plugin.ui

import com.intellij.util.messages.Topic

/**
 * Payload published by editor actions into the chat panel.
 */
data class EditorPrefill(
    val text: String,
    /** Append to the current input instead of replacing it (Add Element Reference). */
    val append: Boolean = false,
    /** One-shot skill armed for the next send; null = no skill (utility attach). */
    val oneShotSkillName: String? = null,
    /** Rendered element body attached as one-shot context; null = nothing to attach. */
    val elementContext: String? = null,
    /** Short chip label, e.g. "function validate". */
    val elementLabel: String? = null
)

/**
 * Project-level message-bus topic: editor actions deliver prepared input
 * into the MaxVibes chat (prefill, not auto-send).
 */
interface ChatInputListener {
    fun onPrefill(prefill: EditorPrefill)

    companion object {
        val TOPIC: Topic<ChatInputListener> =
            Topic.create("MaxVibes chat prefill", ChatInputListener::class.java)
    }
}
