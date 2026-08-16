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
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.ModificationCategory
import com.maxvibes.plugin.service.MaxVibesLogger
import java.time.Instant
import java.util.UUID
import com.maxvibes.domain.model.planning.TaskPlan
import com.maxvibes.domain.model.planning.PlanStep
import com.maxvibes.domain.model.planning.PlanStepStatus
import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.domain.model.chat.CodingAgentSessionRef

@Tag("requestedView")
class XmlRequestedViewInfo {
    @Attribute("path")
    var path: String = ""

    /** Enum name of CodeGranularity. Default FULL for forward compat. */
    @Attribute("granularity")
    var granularity: String = "FULL"

    /** Non-null only for ELEMENT granularity. */
    @Attribute("elementPath")
    var elementPath: String? = null

    constructor()

    constructor(path: String, granularity: String, elementPath: String?) {
        this.path = path; this.granularity = granularity; this.elementPath = elementPath
    }

    fun toDomain(): RequestedViewInfo = RequestedViewInfo(
        path = path,
        granularity = try {
            CodeGranularity.valueOf(granularity)
        } catch (_: IllegalArgumentException) {
            CodeGranularity.FULL
        },
        elementPath = elementPath
    )

    companion object {
        fun fromDomain(v: RequestedViewInfo) =
            XmlRequestedViewInfo(v.path, v.granularity.name, v.elementPath)
    }
}

@Tag("appliedMod")
class XmlAppliedModInfo {
    @Attribute("path")
    var path: String = ""

    /** Enum name of ModificationCategory. Default ELEMENT_LEVEL for forward compat. */
    @Attribute("category")
    var category: String = "ELEMENT_LEVEL"

    constructor()

    constructor(path: String, category: String) {
        this.path = path; this.category = category
    }

    fun toDomain(): AppliedModInfo = AppliedModInfo(
        path = path,
        category = try {
            ModificationCategory.valueOf(category)
        } catch (_: IllegalArgumentException) {
            ModificationCategory.ELEMENT_LEVEL
        }
    )

    companion object {
        fun fromDomain(m: AppliedModInfo) = XmlAppliedModInfo(m.path, m.category.name)
    }
}

@Tag("plan")
class XmlTaskPlan {
    @Attribute("title")
    var title: String = ""

    @Attribute("docPath")
    var docPath: String? = null

    @XCollection(style = XCollection.Style.v2)
    var steps: MutableList<XmlPlanStep> = mutableListOf()

    constructor()

    constructor(title: String, docPath: String?, steps: List<XmlPlanStep>) {
        this.title = title; this.docPath = docPath; this.steps = steps.toMutableList()
    }

    fun toDomain(): TaskPlan =
        TaskPlan(title = title, docPath = docPath, steps = steps.map { it.toDomain() })

    companion object {
        fun fromDomain(p: TaskPlan) =
            XmlTaskPlan(p.title, p.docPath, p.steps.map { XmlPlanStep.fromDomain(it) })
    }
}

@Tag("planStep")
class XmlPlanStep {
    @Attribute("id")
    var id: String = ""

    @Attribute("title")
    var title: String = ""

    /** Enum name of PlanStepStatus. Default PENDING for forward compat. */
    @Attribute("status")
    var status: String = "PENDING"

    @Attribute("docPath")
    var docPath: String? = null

    constructor()

    constructor(id: String, title: String, status: String, docPath: String?) {
        this.id = id; this.title = title; this.status = status; this.docPath = docPath
    }

    fun toDomain(): PlanStep = PlanStep(
        id = id,
        title = title,
        status = try {
            PlanStepStatus.valueOf(status)
        } catch (_: IllegalArgumentException) {
            PlanStepStatus.PENDING
        },
        docPath = docPath
    )

    companion object {
        fun fromDomain(s: PlanStep) = XmlPlanStep(s.id, s.title, s.status.name, s.docPath)
    }
}

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

    /** Typed view requests with granularity. Empty for messages predating this field. */
    @XCollection(style = XCollection.Style.v2)
    var requestedViews: MutableList<XmlRequestedViewInfo> = mutableListOf()

    /** Typed applied modifications with category. Empty for messages predating this field. */
    @XCollection(style = XCollection.Style.v2)
    var appliedModifications: MutableList<XmlAppliedModInfo> = mutableListOf()

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
        requestedViews: List<RequestedViewInfo> = emptyList(),
        appliedModifications: List<AppliedModInfo> = emptyList(),
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
        this.requestedViews = requestedViews.map { XmlRequestedViewInfo.fromDomain(it) }.toMutableList()
        this.appliedModifications = appliedModifications.map { XmlAppliedModInfo.fromDomain(it) }.toMutableList()
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
        requestedViews = requestedViews.map { it.toDomain() },
        appliedModifications = appliedModifications.map { it.toDomain() },
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
            requestedViews = msg.requestedViews,
            appliedModifications = msg.appliedModifications,
            reasoning = msg.reasoning,
            tokenInfo = msg.tokenInfo
        )
    }
}

@Tag("agentCliSession")
class XmlAgentCliSessionState {
    @Attribute("provider")
    var provider: String = "CLAUDE_CODE"

    @Attribute("remoteSessionId")
    var remoteSessionId: String? = null

    @Attribute("needsFullContext")
    var needsFullContext: Boolean = true

    constructor()

    constructor(provider: String, remoteSessionId: String?, needsFullContext: Boolean) {
        this.provider = provider
        this.remoteSessionId = remoteSessionId
        this.needsFullContext = needsFullContext
    }

    fun toDomain(): CodingAgentSessionRef = CodingAgentSessionRef(
        provider = try {
            CodingAgentProvider.valueOf(provider)
        } catch (ignored: IllegalArgumentException) {
            CodingAgentProvider.CLAUDE_CODE
        },
        remoteSessionId = remoteSessionId,
        needsFullContext = needsFullContext
    )

    companion object {
        fun fromDomain(state: CodingAgentSessionRef) = XmlAgentCliSessionState(
            provider = state.provider.name,
            remoteSessionId = state.remoteSessionId,
            needsFullContext = state.needsFullContext
        )
    }
}

@Tag("session")
class XmlChatSession {
    @Attribute("id")
    var id: String = UUID.randomUUID().toString()

    @Attribute("title")
    var title: String = "New Chat"

    /**
     * Пользователь переименовал чат вручную. Default false: в XML, записанных до появления
     * флага, заголовок считается автоматическим, и модель вправе предложить свой.
     */
    @Attribute("titleSetByUser")
    var titleSetByUser: Boolean = false

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

    /**
     * Selected specific prompt name for this session.
     * Empty string = null ("Just Code"). Default empty for backward compat.
     */
    @Attribute("selectedSpecificPromptName")
    var selectedSpecificPromptName: String = ""

    /**
     * Claude Code CLI session id used for --resume. Null for sessions that never ran
     * CLI mode and for XML files written before this field existed.
     */
    @Attribute("claudeCodeSessionId")
    var claudeCodeSessionId: String? = null

    /**
     * Whether the next CLI send must include full context. Default true is the safe
     * fallback for legacy XML files without this attribute.
     */
    @Attribute("claudeCodeNeedsFullContext")
    var claudeCodeNeedsFullContext: Boolean = true

    /** Provider-aware Agent CLI session state. Null in legacy XML files. */
    var agentCliSession: XmlAgentCliSessionState? = null

    /**
     * Task plan of the planner panel. Null for sessions without a plan and for
     * XML files written before this field existed (backward compatible).
     */
    var plan: XmlTaskPlan? = null

    constructor()

    fun toTokenUsage(): TokenUsage = TokenUsage(
        planningInput = planningInputTokens,
        planningOutput = planningOutputTokens,
        chatInput = chatInputTokens,
        chatOutput = chatOutputTokens
    )

    fun toDomain(): ChatSession {
        val migratedAgentState = if (
            codingAgentProvider != null ||
            codingAgentRemoteSessionId != null ||
            !codingAgentNeedsFullContext
        ) {
            CodingAgentSessionRef(
                provider = codingAgentProvider
                    ?.let { runCatching { CodingAgentProvider.valueOf(it) }.getOrNull() }
                    ?: CodingAgentProvider.CLAUDE_CODE,
                remoteSessionId = codingAgentRemoteSessionId,
                needsFullContext = codingAgentNeedsFullContext
            )
        } else {
            agentCliSession?.toDomain()
                ?: if (claudeCodeSessionId != null || !claudeCodeNeedsFullContext) {
                    CodingAgentSessionRef(
                        provider = CodingAgentProvider.CLAUDE_CODE,
                        remoteSessionId = claudeCodeSessionId,
                        needsFullContext = claudeCodeNeedsFullContext
                    )
                } else {
                    null
                }
        }

        return ChatSession(
            id = id,
            title = title,
            titleSetByUser = titleSetByUser,
            parentId = parentId,
            depth = depth,
            messages = messages.map { it.toDomain() },
            tokenUsage = toTokenUsage(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            clipboardStatus = try {
                ClipboardSessionStatus.valueOf(clipboardStatus)
            } catch (ignored: IllegalArgumentException) {
                ClipboardSessionStatus.IDLE
            },
            selectedSpecificPromptName = selectedSpecificPromptName.takeIf { it.isNotEmpty() },
            agentCliSession = migratedAgentState,
            claudeCodeSessionId = claudeCodeSessionId,
            claudeCodeNeedsFullContext = claudeCodeNeedsFullContext,
            plan = plan?.toDomain()
        )
    }

    companion object {
        fun fromDomain(session: ChatSession): XmlChatSession {
            val xml = XmlChatSession()
            xml.id = session.id
            xml.title = session.title
            xml.titleSetByUser = session.titleSetByUser
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
            xml.selectedSpecificPromptName = session.selectedSpecificPromptName ?: ""

            val agentState = session.agentCliSession
            xml.codingAgentProvider = agentState?.provider?.name
            xml.codingAgentRemoteSessionId = agentState?.remoteSessionId
            xml.codingAgentNeedsFullContext = agentState?.needsFullContext ?: true

            // Transitional nested AgentCli state is read for compatibility but no longer written.
            xml.agentCliSession = null

            val claudeState = agentState
                ?.takeIf { it.provider == CodingAgentProvider.CLAUDE_CODE }
            xml.claudeCodeSessionId = claudeState?.remoteSessionId ?: session.claudeCodeSessionId
            xml.claudeCodeNeedsFullContext = claudeState?.needsFullContext
                ?: session.claudeCodeNeedsFullContext

            xml.plan = session.plan?.let { XmlTaskPlan.fromDomain(it) }
            return xml
        }
    }

    @Attribute("codingAgentProvider")
    var codingAgentProvider: String? = null

    @Attribute("codingAgentRemoteSessionId")
    var codingAgentRemoteSessionId: String? = null

    @Attribute("codingAgentNeedsFullContext")
    var codingAgentNeedsFullContext: Boolean = true
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
