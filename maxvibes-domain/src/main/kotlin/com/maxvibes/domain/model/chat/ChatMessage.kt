package com.maxvibes.domain.model.chat

import java.time.Instant
import java.util.UUID
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.modification.AppliedModInfo

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val requestedFiles: List<String> = emptyList(),
    val attachedFiles: List<String> = emptyList(),
    val appliedModificationPaths: List<String> = emptyList(),
    val reasoning: String? = null,
    val tokenInfo: String? = null,
    /**
     * Typed view requests made by the LLM in this ASSISTANT message.
     * Supersedes [requestedFiles] — carries granularity for colour-coded display.
     * Empty for messages created before this field was introduced.
     */
    val requestedViews: List<RequestedViewInfo> = emptyList(),
    /**
     * Typed record of applied modifications with category for colour-coded display.
     * Supersedes [appliedModificationPaths] — carries ModificationCategory.
     * Empty for messages created before this field was introduced.
     */
    val appliedModifications: List<AppliedModInfo> = emptyList()
)
