package com.maxvibes.domain.model.interaction

import com.maxvibes.domain.model.chat.CodingAgentProvider

/**
 * What a coding-agent implementation offers to the UI.
 *
 * A blank model or reasoning level always means "agent default", so neither list
 * carries an explicit Auto entry.
 */
data class CodingAgentCapabilities(
    val displayName: String,
    val models: List<String>,
    val reasoningLevels: List<String>,
    val supportsSubscriptionUsage: Boolean
) {
    companion object {
        fun of(provider: CodingAgentProvider): CodingAgentCapabilities = when (provider) {
            CodingAgentProvider.CLAUDE_CODE -> CodingAgentCapabilities(
                displayName = "Claude Code",
                models = listOf("haiku", "sonnet", "opus"),
                reasoningLevels = listOf("low", "medium", "high", "xhigh", "max"),
                supportsSubscriptionUsage = true
            )

            CodingAgentProvider.CODEX -> CodingAgentCapabilities(
                displayName = "Codex",
                models = listOf(
                    "gpt-5.6-sol",
                    "gpt-5.6-terra",
                    "gpt-5.6-luna",
                    "gpt-5.3-codex-spark",
                    "gpt-5.5",
                    "gpt-5.4",
                    "gpt-5.4-mini"
                ),
                reasoningLevels = listOf("minimal", "low", "medium", "high"),
                supportsSubscriptionUsage = true
            )
        }
    }
}
