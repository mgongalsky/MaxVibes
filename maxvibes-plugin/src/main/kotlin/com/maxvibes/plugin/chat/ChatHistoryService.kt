package com.maxvibes.plugin.chat

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.chat.TokenUsage
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.plugin.service.MaxVibesLogger
import java.time.Instant
import java.util.UUID

/**
 * XML DTO for a single chat message.
 *
 * All collection fields use [XCollection] with [XCollection.Style.v2] so they are stored
 * as nested XML elements. Fields absent in older XML files default to empty — this ensures
 * backward compatibility when new fields are added.
 *
 * Nullable string fields ([reasoning], [tokenInfo]) are stored as XML tags/attributes;
 * if their value is null they are omitted from XML (IntelliJ serializer behaviour).
 */
@Tag("message")
class XmlChatMessage {
    @Attribute("id")
    var id: String = UUID.randomUUID().toString()

    @Attribute("role")
    var role: MessageRole = MessageRole.USER

    @Tag("content")
    var content: String = ""

    @Attribute("timestamp")
    var timestamp: Long = Instant.now().toEpochMilli()

    /** File paths requested by the LLM. Absent in old XML reads as empty list. */
    @XCollection(style = XCollection.Style.v2, elementTypes = [String::class])
    var requestedFiles: MutableList<String> = mutableListOf()

    /** File paths sent TO the LLM (clipboard gathered files). */
    @XCollection(style = XCollection.Style.v2, elementTypes = [String::class])
    var attachedFiles: MutableList<String> = mutableListOf()

    /** String-encoded ElementPath values for each successfully applied modification. */
    @XCollection(style = XCollection.Style.v2, elementTypes = [String::class])
    var appliedModificationPaths: MutableList<String> = mutableListOf()

    /**
     * LLM reasoning / thinking block. Stored as a nested tag (not attribute) because
     * reasoning text can be very long — XML attributes are not suited for multi-line text.
     */
    @Tag("reasoning")
    var reasoning: String? = null

    /** Short token-info summary line. Fits comfortably in an XML attribute. */
    @Attribute("tokenInfo")
    var tokenInfo: String? = null

    constructor()

    constructor(
        id: String,
        role: MessageRole,
        content: String,
        timestamp: Long,
        requestedFiles: List<String> = emptyList(),
        attachedFiles: List<String> = emptyList(),
        appliedModificationPaths: List<String> = emptyList(),
        reasoning: String? = null,
        tokenInfo: String? = null
    ) {
        this.id = id
        this.role = role
        this.content = content
        this.timestamp = timestamp
        this.requestedFiles = requestedFiles.toMutableList()
        this.attachedFiles = attachedFiles.toMutableList()
        this.appliedModificationPaths = appliedModificationPaths.toMutableList()
        this.reasoning = reasoning
        this.tokenInfo = tokenInfo
    }

    /** Converts this XML DTO to the domain [ChatMessage]. */
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = timestamp,
        requestedFiles = requestedFiles.toList(),
        attachedFiles = attachedFiles.toList(),
        appliedModificationPaths = appliedModificationPaths.toList(),
        reasoning = reasoning,
        tokenInfo = tokenInfo
    )

    companion object {
        /** Creates an XML DTO from a domain [ChatMessage] for serialization. */
        fun fromDomain(msg: ChatMessage) = XmlChatMessage(
            id = msg.id,
            role = msg.role,
            content = msg.content,
            timestamp = msg.timestamp,
            requestedFiles = msg.requestedFiles,
            attachedFiles = msg.attachedFiles,
            appliedModificationPaths = msg.appliedModificationPaths,
            reasoning = msg.reasoning,
            tokenInfo = msg.tokenInfo
        )
    }
}

@Tag("session")
class XmlChatSession {
    @Attribute("id")
    var id: String = UUID.randomUUID().toString()

    @Attribute("title")
    var title: String = "New Chat"

    @Attribute("parentId")
    var parentId: String? = null

    @Attribute("depth")
    var depth: Int = 0

    @XCollection(style = XCollection.Style.v2)
    var messages: MutableList<XmlChatMessage> = mutableListOf()

    @Attribute("createdAt")
    var createdAt: Long = Instant.now().toEpochMilli()

    @Attribute("updatedAt")
    var updatedAt: Long = Instant.now().toEpochMilli()

    @Attribute("planningInputTokens")
    var planningInputTokens: Int = 0

    @Attribute("planningOutputTokens")
    var planningOutputTokens: Int = 0

    @Attribute("chatInputTokens")
    var chatInputTokens: Int = 0

    @Attribute("chatOutputTokens")
    var chatOutputTokens: Int = 0

    /**
     * Clipboard session status serialized as a string enum name.
     * Default "IDLE" ensures backward compat with XML files written before this field existed.
     */
    @Attribute("clipboardStatus")
    var clipboardStatus: String = "IDLE"

    constructor()

    fun toTokenUsage(): TokenUsage = TokenUsage(
        planningInput = planningInputTokens,
        planningOutput = planningOutputTokens,
        chatInput = chatInputTokens,
        chatOutput = chatOutputTokens
    )

    /**
     * Converts this XML DTO to the domain [ChatSession].
     * [clipboardStatus] is deserialized with a protective fallback to IDLE in case an
     * unknown/stale value appears in old XML files.
     */
    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        parentId = parentId,
        depth = depth,
        messages = messages.map { it.toDomain() },
        tokenUsage = toTokenUsage(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        clipboardStatus = try {
            ClipboardSessionStatus.valueOf(clipboardStatus)
        } catch (_: IllegalArgumentException) {
            ClipboardSessionStatus.IDLE
        }
    )

    companion object {
        /** Creates an XML DTO from a domain [ChatSession] for serialization. */
        fun fromDomain(session: ChatSession): XmlChatSession {
            val xml = XmlChatSession()
            xml.id = session.id
            xml.title = session.title
            xml.parentId = session.parentId
            xml.depth = session.depth
            xml.messages = session.messages.map { XmlChatMessage.fromDomain(it) }.toMutableList()
            xml.planningInputTokens = session.tokenUsage.planningInput
            xml.planningOutputTokens = session.tokenUsage.planningOutput
            xml.chatInputTokens = session.tokenUsage.chatInput
            xml.chatOutputTokens = session.tokenUsage.chatOutput
            xml.createdAt = session.createdAt
            xml.updatedAt = session.updatedAt
            xml.clipboardStatus = session.clipboardStatus.name
            return xml
        }
    }
}

class ChatHistoryState {
    @XCollection(style = XCollection.Style.v2)
    var sessions: MutableList<XmlChatSession> = mutableListOf()

    var activeSessionId: String? = null

    @XCollection(style = XCollection.Style.v2, elementTypes = [String::class])
    var globalContextFiles: MutableList<String> = mutableListOf()
}

/**
 * Pure persistence adapter for per-project chat history storage.
 *
 * Implements [ChatSessionRepository] — the application-layer port.
 * Manages only XML serialization via IntelliJ [PersistentStateComponent];
 * all business logic lives in [com.maxvibes.application.service.ChatTreeService].
 */
@Service(Service.Level.PROJECT)
@State(
    name = "MaxVibesChatHistory",
    storages = [Storage("maxvibes-chat-history.xml")]
)
class ChatHistoryService : PersistentStateComponent<ChatHistoryState>, ChatSessionRepository {

    private var state = ChatHistoryState()

    override fun getState(): ChatHistoryState = state

    override fun loadState(state: ChatHistoryState) {
        XmlSerializerUtil.copyBean(state, this.state)
        recalculateDepths()
        MaxVibesLogger.info(
            "ChatHistory", "loadState", mapOf(
                "sessions" to state.sessions.size,
                "activeId" to (state.activeSessionId ?: "none"),
                "contextFiles" to state.globalContextFiles.size
            )
        )
    }

    override fun getAllSessions(): List<ChatSession> = state.sessions.map { it.toDomain() }

    override fun getSessionById(id: String): ChatSession? =
        state.sessions.find { it.id == id }?.toDomain()

    override fun getActiveSessionId(): String? = state.activeSessionId

    override fun setActiveSessionId(sessionId: String) {
        state.activeSessionId = sessionId
    }

    override fun saveSession(session: ChatSession) {
        val index = state.sessions.indexOfFirst { it.id == session.id }
        val xml = XmlChatSession.fromDomain(session)
        if (index >= 0) state.sessions[index] = xml
        else state.sessions.add(0, xml)
    }

    override fun deleteSession(sessionId: String) {
        state.sessions.removeIf { it.id == sessionId }
        if (state.activeSessionId == sessionId) {
            state.activeSessionId = state.sessions.firstOrNull()?.id
        }
    }

    override fun getGlobalContextFiles(): List<String> = state.globalContextFiles.toList()

    override fun setGlobalContextFiles(files: List<String>) {
        state.globalContextFiles = files.distinct().toMutableList()
    }

    // ── Depth recalculation ────────────────────────────────────────────────────

    private fun recalculateChildDepths(sessionId: String) {
        val parent = state.sessions.find { it.id == sessionId } ?: return
        state.sessions.filter { it.parentId == sessionId }.forEach { child ->
            child.depth = parent.depth + 1
            recalculateChildDepths(child.id)
        }
    }

    private fun recalculateDepths() {
        state.sessions.filter { it.parentId == null }.forEach { root ->
            root.depth = 0
            recalculateChildDepths(root.id)
        }
    }

    companion object {
        fun getInstance(project: Project): ChatHistoryService =
            project.getService(ChatHistoryService::class.java)
    }
}
