package com.maxvibes.plugin.ui

/** The Claude Code CLI settings the model and effort combos are bound to. */
interface ClaudeCliSettings {
    var model: String
    var effortLevel: String
}

/**
 * Two-way binding between the CLI combos and settings.
 *
 * Writing to a combo programmatically fires its listeners, so each direction is
 * guarded: a commit arriving while its own sync is in flight is ignored.
 */
class ClaudeCliSettingsBinder(
    private val settings: ClaudeCliSettings,
    private val onStatus: (String) -> Unit
) {

    private var suppressModel = false
    private var suppressEffort = false

    fun syncModel(show: (String) -> Unit) {
        suppressModel = true
        try {
            val value = settings.model.trim()
            show(if (value.isEmpty()) AUTO else value)
        } finally {
            suppressModel = false
        }
    }

    /** The model combo is editable, so its raw value is trimmed and matched loosely. */
    fun commitModel(raw: Any?) {
        if (suppressModel) return
        val text = raw?.toString()?.trim() ?: return
        val value = if (text.isEmpty() || text.equals(AUTO, ignoreCase = true)) "" else text
        if (settings.model == value) return
        settings.model = value
        onStatus("CLI model: ${value.ifEmpty { AUTO }} \u2014 applies on next send")
    }

    fun syncEffort(show: (String) -> Unit) {
        suppressEffort = true
        try {
            show(settings.effortLevel.ifBlank { AUTO })
        } finally {
            suppressEffort = false
        }
    }

    /** The effort combo is a fixed list, so only an exact String selection is accepted. */
    fun commitEffort(raw: Any?) {
        if (suppressEffort) return
        val text = raw as? String ?: return
        val value = if (text == AUTO) "" else text
        if (settings.effortLevel == value) return
        settings.effortLevel = value
        onStatus("CLI effort: ${value.ifEmpty { AUTO }} \u2014 applies on next send")
    }

    private companion object {
        const val AUTO = "Auto"
    }
}
