package com.maxvibes.application.port.output

import com.maxvibes.domain.model.chat.ChatSession
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Проверяем что интерфейс корректен через in-memory реализацию.
 * Это также служит примером как тестировать ChatTreeService на шаге 7.
 */
class InMemoryChatSessionRepository : ChatSessionRepository {
    private val sessions = mutableMapOf<String, ChatSession>()
    private var activeSessionId: String? = null
    private var contextFiles = mutableListOf<String>()

    override fun getAllSessions(): List<ChatSession> = sessions.values.toList()
    override fun getSessionById(id: String): ChatSession? = sessions[id]
    override fun getActiveSessionId(): String? = activeSessionId
    override fun setActiveSessionId(sessionId: String) {
        activeSessionId = sessionId
    }

    override fun saveSession(session: ChatSession) {
        sessions[session.id] = session
    }

    override fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        if (activeSessionId == sessionId) activeSessionId = sessions.keys.firstOrNull()
    }

    override fun getGlobalContextFiles(): List<String> = contextFiles.toList()
    override fun setGlobalContextFiles(files: List<String>) {
        contextFiles = files.distinct().toMutableList()
    }
}

class ChatSessionRepositoryContractTest {
    private lateinit var repo: ChatSessionRepository

    @BeforeEach
    fun setup() {
        repo = InMemoryChatSessionRepository()
    }

    @Test
    fun `getAllSessions returns empty initially`() {
        assertTrue(repo.getAllSessions().isEmpty())
    }

    @Test
    fun `saveSession and getSessionById`() {
        val session = ChatSession(id = "s1", title = "Test")
        repo.saveSession(session)
        val loaded = repo.getSessionById("s1")
        assertNotNull(loaded)
        assertEquals("Test", loaded!!.title)
    }

    @Test
    fun `saveSession updates existing session`() {
        val session = ChatSession(id = "s1", title = "Original")
        repo.saveSession(session)
        repo.saveSession(session.withTitle("Updated"))
        assertEquals("Updated", repo.getSessionById("s1")?.title)
        assertEquals(1, repo.getAllSessions().size)
    }

    @Test
    fun `deleteSession removes session`() {
        val session = ChatSession(id = "s1")
        repo.saveSession(session)
        repo.deleteSession("s1")
        assertNull(repo.getSessionById("s1"))
        assertTrue(repo.getAllSessions().isEmpty())
    }

    @Test
    fun `activeSessionId persists`() {
        assertNull(repo.getActiveSessionId())
        repo.setActiveSessionId("s1")
        assertEquals("s1", repo.getActiveSessionId())
    }

    @Test
    fun `globalContextFiles persist`() {
        assertTrue(repo.getGlobalContextFiles().isEmpty())
        repo.setGlobalContextFiles(listOf("src/Main.kt", "README.md"))
        assertEquals(listOf("src/Main.kt", "README.md"), repo.getGlobalContextFiles())
    }

    @Test
    fun `globalContextFiles deduplicates`() {
        repo.setGlobalContextFiles(listOf("a.kt", "a.kt", "b.kt"))
        assertEquals(2, repo.getGlobalContextFiles().size)
    }
}
