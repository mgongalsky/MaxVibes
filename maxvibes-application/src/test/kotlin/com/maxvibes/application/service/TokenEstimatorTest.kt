package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.InteractionHistoryEntry
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionPhase
import com.maxvibes.domain.model.interaction.InteractionResponse
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TokenEstimatorTest {

    @Test
    fun `request estimate sums all text fields and divides by 4`() {
        val request = ClipboardRequest(
            phase = InteractionPhase.CHAT,
            currentMessage = "m".repeat(10),
            projectName = "ignored-in-estimate",
            systemInstruction = "s".repeat(40),
            fileTree = "t".repeat(20),
            freshFiles = mapOf("src/Foo.kt" to "f".repeat(30)),
            chatHistory = listOf(InteractionHistoryEntry(role = "user", content = "h".repeat(10))),
            attachedContext = "a".repeat(10),
            specificPrompt = "p".repeat(10),
            ideErrors = "e".repeat(10),
            commandResults = "c".repeat(10)
        )
        // 40+20+30+10+10+10+10+10+10 = 150 chars -> 150/4 = 37
        assertEquals(37, TokenEstimator.estimateTokens(request))
    }

    @Test
    fun `request estimate adds flat 1100 tokens per attached image`() {
        val request = ClipboardRequest(
            phase = InteractionPhase.CHAT,
            currentMessage = "abcd",
            projectName = "p",
            attachedImages = listOf(
                AttachedImage(mediaType = "image/png", base64Data = "AAAA"),
                AttachedImage(mediaType = "image/png", base64Data = "BBBB")
            )
        )
        // 4 chars / 4 = 1, plus 2 * 1100
        assertEquals(2201, TokenEstimator.estimateTokens(request))
    }

    @Test
    fun `response estimate sums message reasoning commit and modification contents`() {
        val response = InteractionResponse(
            message = "m".repeat(20),
            reasoning = "r".repeat(10),
            commitMessage = "c".repeat(10),
            modifications = listOf(
                InteractionModification(type = "CREATE_FILE", path = "file:src/New.kt", content = "x".repeat(40))
            )
        )
        // 20+10+10+40 = 80 chars -> 80/4 = 20
        assertEquals(20, TokenEstimator.estimateOutputTokens(response))
    }

    @Test
    fun `empty response estimates to zero`() {
        assertEquals(0, TokenEstimator.estimateOutputTokens(InteractionResponse()))
    }
}
