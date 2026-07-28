package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.InteractionResponse

/**
 * Heuristic token estimation for Claude Code turns: total chars / 4 plus a flat
 * per-image cost.
 *
 * Pins the Claude Code variant of the estimate: counts currentMessage,
 * specificPrompt, ideErrors and attached images. [ClipboardInteractionService]
 * keeps its own leaner estimate by design — unifying them would change reported
 * numbers and belongs to a deliberate behaviour-change step, not a refactor.
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN = 4

    /** Rough cost of one attached screenshot (≤1568px ≈ 1.1–1.6k tokens). */
    private const val TOKENS_PER_IMAGE = 1100

    fun estimateTokens(request: ClipboardRequest): Int {
        val textSize = request.systemInstruction.length +
                request.fileTree.length +
                request.freshFiles.values.sumOf { it.length } +
                request.chatHistory.sumOf { it.content.length } +
                request.currentMessage.length +
                (request.attachedContext?.length ?: 0) +
                (request.specificPrompt?.length ?: 0) +
                (request.ideErrors?.length ?: 0) +
                (request.commandResults?.length ?: 0)
        val imageTokens = request.attachedImages.size * TOKENS_PER_IMAGE
        return textSize / CHARS_PER_TOKEN + imageTokens
    }

    fun estimateOutputTokens(response: InteractionResponse): Int {
        val text = response.message.length +
                (response.reasoning?.length ?: 0) +
                (response.commitMessage?.length ?: 0) +
                response.modifications.sumOf { it.content.length }
        return text / CHARS_PER_TOKEN
    }
}
