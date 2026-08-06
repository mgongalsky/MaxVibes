package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.domain.model.modification.ModificationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatSessionUiCoordinatorTest {

    @Test
    fun `load renders transcript clears navigation and refreshes`() {
        val fixture = Fixture(
            active = ChatSession(
                id = "active",
                messages = listOf(ChatMessage(role = MessageRole.USER, content = "Hello"))
            )
        )

        fixture.coordinator().loadCurrentSession()

        assertEquals(listOf("clear", "user:Hello"), fixture.view.events)
        assertEquals(1, fixture.navigationClearCount)
        assertEquals(1, fixture.refreshCount)
    }

    @Test
    fun `empty session renders welcome state`() {
        val fixture = Fixture(active = ChatSession(messages = emptyList()))
        fixture.contextCount = 2
        fixture.mode = InteractionMode.CLAUDE_CODE

        fixture.coordinator().loadCurrentSession()

        assertTrue(fixture.view.events.contains("system:MaxVibes  •  Claude Code — local CLI process"))
        assertTrue(fixture.view.events.contains("system:📎 2 global context file(s) active"))
        assertTrue(fixture.view.events.contains("system:Type your task • Ctrl+Enter to send"))
    }

    @Test
    fun `create branch clears attachments and delegates selected title`() {
        val fixture = Fixture(active = ChatSession(id = "parent", title = "Main session"))
        fixture.dialogs.branchTitle = "Feature branch"

        fixture.coordinator().createBranch()

        assertEquals(1, fixture.attachmentsClearCount)
        assertEquals(listOf("parent" to "Feature branch"), fixture.branches)
        assertEquals(listOf("Branch: Feature branch"), fixture.statuses)
    }

    @Test
    fun `cancelled delete leaves session untouched`() {
        val fixture = Fixture(active = ChatSession(id = "active"))
        fixture.dialogs.deleteConfirmed = false

        fixture.coordinator().deleteCurrentChat()

        assertTrue(fixture.deleted.isEmpty())
        assertEquals(0, fixture.attachmentsClearCount)
    }

    @Test
    fun `confirmed delete clears attachments and delegates session id`() {
        val fixture = Fixture(active = ChatSession(id = "active"))
        fixture.dialogs.deleteConfirmed = true

        fixture.coordinator().deleteCurrentChat()

        assertEquals(listOf("active"), fixture.deleted)
        assertEquals(1, fixture.attachmentsClearCount)
        assertEquals(listOf("Chat deleted"), fixture.statuses)
    }

    @Test
    fun `select session activates it before loading`() {
        val fixture = Fixture(active = ChatSession(id = "old"))
        fixture.sessions["new"] = ChatSession(id = "new")

        fixture.coordinator().selectSession("new")

        assertEquals("new", fixture.active.id)
        assertEquals(1, fixture.refreshCount)
    }

    private class RecordingView : SessionTranscriptView {
        val events = mutableListOf<String>()

        override fun clear() {
            events += "clear"
        }

        override fun addUser(text: String) {
            events += "user:$text"
        }

        override fun addAssistant(
            message: DisplayMessage,
            modifications: List<ModificationResult>
        ) {
            events += "assistant:${message.content}"
        }

        override fun addSystem(text: String) {
            events += "system:$text"
        }
    }

    private class FakeDialogs : ChatSessionDialogs {
        var branchTitle: String? = null
        var deleteConfirmed = true

        override fun requestBranchTitle(defaultTitle: String): String? = branchTitle

        override fun confirmDelete(session: ChatSession, childCount: Int): Boolean =
            deleteConfirmed
    }

    private class Fixture(active: ChatSession) {
        val sessions = mutableMapOf(active.id to active)
        var active: ChatSession = active
        val view = RecordingView()
        val dialogs = FakeDialogs()
        var mode = InteractionMode.API
        var contextCount = 0
        var navigationClearCount = 0
        var refreshCount = 0
        var attachmentsClearCount = 0
        val statuses = mutableListOf<String>()
        val branches = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()

        fun coordinator() = ChatSessionUiCoordinator(
            activeSession = { active },
            sessionPath = { listOf(active) },
            parentSession = { null },
            childCount = { 0 },
            setActiveSession = { id -> active = sessions.getValue(id) },
            transcriptRenderer = SessionTranscriptRenderer(),
            transcriptView = view,
            dialogs = dialogs,
            currentMode = { mode },
            contextFilesCount = { contextCount },
            clearNavigation = { navigationClearCount++ },
            registerModifications = {},
            clearAttachments = { attachmentsClearCount++ },
            createSession = {},
            branchSession = { id, title -> branches += id to title },
            deleteSession = { deleted += it },
            renameSession = { _, _ -> },
            onStatus = { statuses += it },
            onRefresh = { refreshCount++ }
        )
    }
}
