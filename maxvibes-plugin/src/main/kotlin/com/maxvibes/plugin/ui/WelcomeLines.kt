package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.InteractionMode

/** System bubbles shown when an empty session is opened. */
object WelcomeLines {

    fun build(
        mode: InteractionMode,
        isBranch: Boolean,
        parentTitle: String?,
        contextFilesCount: Int
    ): List<String> {
        val lines = mutableListOf("MaxVibes  \u2022  ${modeCaption(mode)}")
        // The closing quote lives in the fallback, so a resolved title renders unquoted.
        if (isBranch) lines += "\u2514 Branch from: \"" + (parentTitle ?: "?\"")
        if (contextFilesCount > 0) lines += "\uD83D\uDCCE $contextFilesCount global context file(s) active"
        lines += "Type your task \u2022 Ctrl+Enter to send"
        return lines
    }

    private fun modeCaption(mode: InteractionMode): String = when (mode) {
        InteractionMode.API -> "API \u2014 direct LLM calls"
        InteractionMode.CLIPBOARD -> "Clipboard \u2014 paste JSON into Claude/ChatGPT"
        InteractionMode.CHEAP_API -> "Cheap API \u2014 budget model"
        InteractionMode.CLAUDE_CODE -> "Claude Code \u2014 local CLI process"
    }
}
