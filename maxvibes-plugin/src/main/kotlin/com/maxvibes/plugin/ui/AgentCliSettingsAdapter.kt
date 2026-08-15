package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.plugin.settings.MaxVibesSettings

/** Routes the chat CLI combos to the settings fields of the currently selected coding agent. */
class AgentCliSettingsAdapter(
    private val settings: MaxVibesSettings
) : ClaudeCliSettings {

    override var provider: CodingAgentProvider
        get() = runCatching {
            CodingAgentProvider.valueOf(settings.codingAgentProvider)
        }.getOrDefault(CodingAgentProvider.CLAUDE_CODE)
        set(value) {
            settings.codingAgentProvider = value.name
        }

    override var model: String
        get() = when (provider) {
            CodingAgentProvider.CLAUDE_CODE -> settings.claudeCodeModel
            CodingAgentProvider.CODEX -> settings.codexModel
        }
        set(value) {
            when (provider) {
                CodingAgentProvider.CLAUDE_CODE -> settings.claudeCodeModel = value
                CodingAgentProvider.CODEX -> settings.codexModel = value
            }
        }

    override var effortLevel: String
        get() = when (provider) {
            CodingAgentProvider.CLAUDE_CODE -> settings.claudeCodeEffortLevel
            CodingAgentProvider.CODEX -> settings.codexReasoningEffort
        }
        set(value) {
            when (provider) {
                CodingAgentProvider.CLAUDE_CODE -> settings.claudeCodeEffortLevel = value
                CodingAgentProvider.CODEX -> settings.codexReasoningEffort = value
            }
        }
}
