package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.application.port.output.PromptPort
import com.maxvibes.application.port.output.PromptTemplates
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.shared.result.Result

/**
 * Owns the in-memory Claude Code workspace and its reconstruction from persisted chat data.
 *
 * Session-state transitions and transport execution remain outside this class. This service
 * is responsible only for creating, updating, restoring and clearing ClipboardSessionState.
 */
internal class ClaudeCodeWorkspaceService(
    private val contextProvider: ProjectContextPort,
    private val promptPort: PromptPort,
    private val chatSessionRepository: ChatSessionRepository,
    private val notificationPort: NotificationPort,
    private val logger: LoggerPort? = null
) {
    private val workspace = ClaudeCodeWorkspaceHolder()

    val state: ClipboardSessionState?
        get() = workspace.state

    val owner: String?
        get() = workspace.owner

    fun isOwnedBy(sessionId: String): Boolean =
        workspace.isOwnedBy(sessionId)

    suspend fun start(
        command: UserInputCommand
    ): ClaudeCodeWorkspaceResult {
        notificationPort.showProgress("Gathering project context...", 0.1)
        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            return ClaudeCodeWorkspaceResult.Failure(
                "Failed to get project context: ${projectContextResult.error.message}"
            )
        }

        val projectContext = (projectContextResult as Result.Success).value
        val claudeSystem = promptPort.claudeCodeSystem()
        val prompts = PromptTemplates(
            chatSystem = claudeSystem,
            planningSystem = claudeSystem
        )
        val newState = ClipboardSessionState(
            currentMessage = command.userInput,
            projectContext = projectContext,
            dialogHistory = command.history.toMutableList(),
            prompts = prompts,
            allGatheredFiles = mutableMapOf(),
            planOnly = command.planOnly
        )

        workspace.install(command.sessionId, newState)
        appendHistory(ChatRole.USER, command.userInput)
        return ClaudeCodeWorkspaceResult.Ready(newState)
    }

    suspend fun continueSession(
        command: UserInputCommand
    ): ClaudeCodeWorkspaceResult {
        if (!workspace.isOwnedBy(command.sessionId) && !restore(command.sessionId)) {
            return ClaudeCodeWorkspaceResult.Failure(
                "Cannot restore session state for session ${command.sessionId}. Please start a new task."
            )
        }

        val currentState = workspace.state
            ?: return ClaudeCodeWorkspaceResult.Failure("No active workspace")
        val updatedState = currentState.copy(
            currentMessage = command.userInput,
            planOnly = command.planOnly
        )

        workspace.install(command.sessionId, updatedState)
        appendHistory(ChatRole.USER, command.userInput)
        return ClaudeCodeWorkspaceResult.Ready(updatedState)
    }

    suspend fun ensure(sessionId: String): Boolean {
        if (workspace.isOwnedBy(sessionId) && workspace.state != null) {
            return true
        }
        return restore(sessionId)
    }

    fun appendAssistantHistory(content: String) {
        appendHistory(ChatRole.ASSISTANT, content)
    }

    fun clear() {
        workspace.clear()
    }

    private suspend fun restore(sessionId: String): Boolean {
        val session = chatSessionRepository.getSessionById(sessionId) ?: return false
        val lastUserMessage = session.messages
            .lastOrNull { it.role == MessageRole.USER }
            ?.content
            ?: return false

        val projectContextResult = contextProvider.getProjectContext()
        if (projectContextResult is Result.Failure) {
            log("ERROR: Failed to get project context during workspace restore: ${projectContextResult.error.message}")
            return false
        }

        val projectContext = (projectContextResult as Result.Success).value
        val claudeSystem = promptPort.claudeCodeSystem()
        val prompts = PromptTemplates(
            chatSystem = claudeSystem,
            planningSystem = claudeSystem
        )
        val restoredState = ClipboardSessionState(
            currentMessage = lastUserMessage,
            projectContext = projectContext,
            dialogHistory = session.messages
                .filter {
                    it.role == MessageRole.USER ||
                            it.role == MessageRole.ASSISTANT
                }
                .map { message ->
                    ChatMessageDTO(
                        role = if (message.role == MessageRole.USER) {
                            ChatRole.USER
                        } else {
                            ChatRole.ASSISTANT
                        },
                        content = message.content
                    )
                }
                .toMutableList(),
            prompts = prompts,
            allGatheredFiles = mutableMapOf(),
            planOnly = false
        )

        workspace.install(sessionId, restoredState)
        log("Workspace restored from domain: sessionId=$sessionId, messages=${session.messages.size}")
        return true
    }

    private fun appendHistory(role: ChatRole, content: String) {
        workspace.state?.dialogHistory?.add(
            ChatMessageDTO(
                role = role,
                content = content
            )
        )
    }

    private fun log(message: String) {
        println("[MaxVibes ClaudeCode] $message")
        logger?.info("ClaudeCode", message)
    }
}

internal sealed interface ClaudeCodeWorkspaceResult {
    data class Ready(
        val state: ClipboardSessionState
    ) : ClaudeCodeWorkspaceResult

    data class Failure(
        val message: String
    ) : ClaudeCodeWorkspaceResult
}
