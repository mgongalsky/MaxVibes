package com.maxvibes.domain.model.chat

data class TokenUsage(
    val planningInput: Int = 0,
    val planningOutput: Int = 0,
    val chatInput: Int = 0,
    val chatOutput: Int = 0
) {
    val totalInput: Int get() = planningInput + chatInput
    val totalOutput: Int get() = planningOutput + chatOutput
    val total: Int get() = totalInput + totalOutput

    fun addPlanning(input: Int, output: Int): TokenUsage =
        copy(planningInput = planningInput + input, planningOutput = planningOutput + output)

    fun addChat(input: Int, output: Int): TokenUsage =
        copy(chatInput = chatInput + input, chatOutput = chatOutput + output)

    fun isEmpty(): Boolean = total == 0

    fun formatDisplay(
        inputCostPerMillion: Double = 3.0,
        outputCostPerMillion: Double = 15.0
    ): String {
        if (isEmpty()) return ""
        val parts = mutableListOf<String>()
        val planTokens = planningInput + planningOutput
        val chatTokens = chatInput + chatOutput
        if (planTokens > 0)
            parts += "Plan: in ${formatTok(planningInput)} / out ${formatTok(planningOutput)}"
        if (chatTokens > 0)
            parts += "Chat: in ${formatTok(chatInput)} / out ${formatTok(chatOutput)}"
        val cost = totalInput / 1_000_000.0 * inputCostPerMillion +
                totalOutput / 1_000_000.0 * outputCostPerMillion
        parts += "~\$${String.format(java.util.Locale.US, "%.3f", cost)}"
        return parts.joinToString("  |  ")
    }

    private fun formatTok(n: Int): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
        n >= 1_000 -> "${n / 1_000}k"
        else -> n.toString()
    }

    companion object {
        val EMPTY = TokenUsage()
    }
}
