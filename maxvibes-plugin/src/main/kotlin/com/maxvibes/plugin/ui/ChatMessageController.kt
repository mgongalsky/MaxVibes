package com.maxvibes.plugin.ui

import com.intellij.openapi.project.Project
import com.maxvibes.application.service.ClipboardStepResult
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.service.MaxVibesService

/**
 * Aggregate UI port implemented by ChatPanel and shared test fakes.
 * Production collaborators depend on narrower facets from ChatPanelViews.kt.
 */
interface ChatPanelCallbacks :
    MessageFlowView,
    AttachmentView,
    SessionView,
    QuestionView,
    CommandView

/**
 * Thin public facade for chat interactions.
 *
 * Construction and orchestration live in [ChatMessageControllerComposition];
 * this class preserves the stable API consumed by ChatPanel and editor actions.
 */
class ChatMessageController(
    project: Project,
    service: MaxVibesService,
    callbacks: ChatPanelCallbacks
) {
    private val composition = ChatMessageControllerComposition(project, service, callbacks)

    val attachedTrace: String?
        get() = composition.attachedTrace

    val attachedErrors: String?
        get() = composition.attachedErrors

    companion object {
        /** Compatibility entry point; new code should use TaskContextFormatter directly. */
        fun buildTaskWithContext(task: String, trace: String?, errs: String?): String =
            TaskContextFormatter.build(task, trace, errs)
    }

    fun runClipboardBg(
        title: String,
        session: ChatSession,
        action: suspend () -> ClipboardStepResult
    ): Unit = composition.runClipboardBg(title, session, action)

    fun redoClipboardJson() = composition.redoClipboardJson()

    fun approve() = composition.approve()

    fun attachTrace(traceContent: String) = composition.attachTrace(traceContent)

    fun clearTrace() = composition.clearTrace()

    fun clearErrors() = composition.clearErrors()

    fun attachImage(image: AttachedImage): Boolean = composition.attachImage(image)

    fun clearImages() = composition.clearImages()

    fun removeImage(index: Int) = composition.removeImage(index)

    fun fetchIdeErrors() = composition.fetchIdeErrors()

    fun clearAttachmentsAfterSend() = composition.clearAttachmentsAfterSend()

    fun createNewSession() = composition.createNewSession()

    fun deleteCurrentSession(sessionId: String) = composition.deleteCurrentSession(sessionId)

    fun renameSession(sessionId: String, newTitle: String) =
        composition.renameSession(sessionId, newTitle)

    fun branchSession(parentSessionId: String, title: String) =
        composition.branchSession(parentSessionId, title)

    fun loadSession(sessionId: String) = composition.loadSession(sessionId)

    fun selectSpecificPrompt(name: String?) = composition.selectSpecificPrompt(name)

    fun sendMessage(
        userInput: String,
        isPlanOnly: Boolean,
        isDryRun: Boolean,
        mode: InteractionMode,
        addHistory: Boolean = false,
        selectedSpecificPromptName: String? = null
    ) = composition.sendMessage(
        userInput = userInput,
        isPlanOnly = isPlanOnly,
        isDryRun = isDryRun,
        mode = mode,
        addHistory = addHistory,
        selectedSpecificPromptName = selectedSpecificPromptName
    )

    fun armOneShot(skillName: String?, elementContext: String?, label: String) =
        composition.armOneShot(skillName, elementContext, label)

    fun clearOneShot() = composition.clearOneShot()
    fun isAllowAllApprovals(): Boolean = composition.isAllowAllApprovals()
    fun setAllowAllApprovals(enabled: Boolean) = composition.setAllowAllApprovals(enabled)
}
