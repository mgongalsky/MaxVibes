package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.domain.model.interaction.CodingAgentCapabilities

/** Shown and accepted in place of a blank setting, which means "agent default". */
internal const val AGENT_SETTING_AUTO = "Auto"

interface ClaudeCliSettings {
    var provider: CodingAgentProvider
    var model: String
    var effortLevel: String
}

/**
 * Two-way binding between the CLI combos and settings.
 *
 * Writing to a combo programmatically fires its listeners, so each direction is
 * guarded: a commit arriving while its own sync is in flight is ignored. The
 * guards are exposed so the owner can also rebuild the item lists safely.
 */
class ClaudeCliSettingsBinder(
    private val settings: ClaudeCliSettings,
    private val onStatus: (String) -> Unit
) {

    private var suppressModel = false
    private var suppressEffort = false

    val capabilities: CodingAgentCapabilities
        get() = CodingAgentCapabilities.of(settings.provider)

    fun <T> suppressingModel(block: () -> T): T {
        suppressModel = true
        try {
            return block()
        } finally {
            suppressModel = false
        }
    }

    fun <T> suppressingEffort(block: () -> T): T {
        suppressEffort = true
        try {
            return block()
        } finally {
            suppressEffort = false
        }
    }

    fun syncModel(show: (String) -> Unit) = suppressingModel {
        val value = settings.model.trim()
        show(if (value.isEmpty()) AGENT_SETTING_AUTO else value)
    }

    /** The model combo is editable, so its raw value is trimmed and matched loosely. */
    fun commitModel(raw: Any?) {
        if (suppressModel) return
        val text = raw?.toString()?.trim() ?: return
        val value = if (text.isEmpty() || text.equals(AGENT_SETTING_AUTO, ignoreCase = true)) "" else text
        if (settings.model == value) return
        settings.model = value
        onStatus(
            "${capabilities.displayName} model: ${value.ifEmpty { AGENT_SETTING_AUTO }} \u2014 applies on next send"
        )
    }

    fun syncEffort(show: (String) -> Unit) = suppressingEffort {
        show(settings.effortLevel.ifBlank { AGENT_SETTING_AUTO })
    }

    /** The effort combo is a fixed list, so only an exact String selection is accepted. */
    fun commitEffort(raw: Any?) {
        if (suppressEffort) return
        val text = raw as? String ?: return
        val value = if (text == AGENT_SETTING_AUTO) "" else text
        if (settings.effortLevel == value) return
        settings.effortLevel = value
        onStatus(
            "${capabilities.displayName} effort: ${value.ifEmpty { AGENT_SETTING_AUTO }} \u2014 applies on next send"
        )
    }
}
