package com.maxvibes.plugin.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class TokenUsageAccumulator {

    var planningInputTokens: Int = 0
        private set
    var planningOutputTokens: Int = 0
        private set
    var chatInputTokens: Int = 0
        private set
    var chatOutputTokens: Int = 0
        private set

    val planningTokens: Int get() = planningInputTokens + planningOutputTokens
    val chatTokens: Int get() = chatInputTokens + chatOutputTokens
    val totalTokens: Int get() = planningTokens + chatTokens

    fun addPlanning(inputTokens: Int, outputTokens: Int) {
        planningInputTokens += inputTokens
        planningOutputTokens += outputTokens
    }

    fun addChat(inputTokens: Int, outputTokens: Int) {
        chatInputTokens += inputTokens
        chatOutputTokens += outputTokens
    }

    fun reset() {
        planningInputTokens = 0
        planningOutputTokens = 0
        chatInputTokens = 0
        chatOutputTokens = 0
    }

    fun formatDisplay(
        inputCostPerMillion: Double = 3.0,
        outputCostPerMillion: Double = 15.0
    ): String {
        if (totalTokens == 0) return ""
        val parts = mutableListOf<String>()
        if (planningTokens > 0)
            parts += "Plan: in ${formatTok(planningInputTokens)} / out ${formatTok(planningOutputTokens)}"
        if (chatTokens > 0)
            parts += "Chat: in ${formatTok(chatInputTokens)} / out ${formatTok(chatOutputTokens)}"
        val cost = (planningInputTokens + chatInputTokens) / 1_000_000.0 * inputCostPerMillion +
                (planningOutputTokens + chatOutputTokens) / 1_000_000.0 * outputCostPerMillion
        val costStr = String.format("%.3f", cost)
        parts += "~\$$costStr"
        return parts.joinToString("  |  ")
    }

    private fun formatTok(n: Int): String = when {
        n >= 1_000_000 -> {
            val millions = n / 1_000_000
            val decimals = (n % 1_000_000) / 100_000
            "${millions}.${decimals}M"
        }
        n >= 1_000 -> "${n / 1_000}k"
        else -> n.toString()
    }
    companion object {
        fun getInstance(project: Project): TokenUsageAccumulator =
            project.getService(TokenUsageAccumulator::class.java)
    }
}