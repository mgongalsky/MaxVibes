package com.maxvibes.application.service


import com.maxvibes.application.port.output.InMemoryChatSessionRepository
import com.maxvibes.domain.model.chat.MessageRole
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChatTreeServiceTest {
    private lateinit var repo: InMemoryChatSessionRepository
    private lateinit var service: ChatTreeService

    @BeforeEach
    fun setup() {
        repo = InMemoryChatSessionRepository()
        service = ChatTreeService(repo)
    }

    // --- createNewSession ---

    @Test
    fun `createNewSession creates root session`() {
        val session = service.createNewSession()
        assertTrue(session.isRoot)
        assertEquals(0, session.depth)
        assertNotNull(repo.getSessionById(session.id))
    }

    @Test
    fun `createNewSession sets it as active`() {
        val session = service.createNewSession()
        assertEquals(session.id, repo.getActiveSessionId())
    }

    @Test
    fun `getActiveSession creates new session when none exists`() {
        val session = service.getActiveSession()
        assertNotNull(session)
    }

    @Test
    fun `getActiveSession returns existing active session`() {
        val created = service.createNewSession()
        val active = service.getActiveSession()
        assertEquals(created.id, active.id)
    }

    // --- createBranch ---

    @Test
    fun `createBranch creates child with correct parentId and depth`() {
        val parent = service.createNewSession()
        val branch = service.createBranch(parent.id)
        assertNotNull(branch)
        assertEquals(parent.id, branch!!.parentId)
        assertEquals(1, branch.depth)
        assertFalse(branch.isRoot)
    }

    @Test
    fun `createBranch sets branch as active`() {
        val parent = service.createNewSession()
        val branch = service.createBranch(parent.id)
        assertEquals(branch!!.id, repo.getActiveSessionId())
    }

    @Test
    fun `createBranch with custom title uses it`() {
        val parent = service.createNewSession()
        val branch = service.createBranch(parent.id, "My Branch")
        assertEquals("My Branch", branch!!.title)
    }

    @Test
    fun `createBranch returns null for nonexistent parent`() {
        assertNull(service.createBranch("nonexistent"))
    }

    @Test
    fun `nested branch has depth 2`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val grandchild = service.createBranch(child.id)!!
        assertEquals(0, root.depth)
        assertEquals(1, child.depth)
        assertEquals(2, grandchild.depth)
    }

    // --- getChildren ---

    @Test
    fun `getChildren returns empty for leaf session`() {
        val session = service.createNewSession()
        assertTrue(service.getChildren(session.id).isEmpty())
    }

    @Test
    fun `getChildren returns direct children only`() {
        val root = service.createNewSession()
        val child1 = service.createBranch(root.id)!!
        val child2 = service.createBranch(root.id)!!
        service.createBranch(child1.id)!!

        val children = service.getChildren(root.id)
        assertEquals(2, children.size)
        assertTrue(children.any { it.id == child1.id })
        assertTrue(children.any { it.id == child2.id })
    }

    // --- getSessionPath ---

    @Test
    fun `getSessionPath for root returns single element`() {
        val root = service.createNewSession()
        val path = service.getSessionPath(root.id)
        assertEquals(1, path.size)
        assertEquals(root.id, path[0].id)
    }

    @Test
    fun `getSessionPath for child returns root then child`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val path = service.getSessionPath(child.id)
        assertEquals(2, path.size)
        assertEquals(root.id, path[0].id)
        assertEquals(child.id, path[1].id)
    }

    @Test
    fun `getSessionPath for grandchild returns full path`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val grandchild = service.createBranch(child.id)!!
        val path = service.getSessionPath(grandchild.id)
        assertEquals(3, path.size)
        assertEquals(root.id, path[0].id)
        assertEquals(child.id, path[1].id)
        assertEquals(grandchild.id, path[2].id)
    }

    // --- deleteSession ---

    @Test
    fun `deleteSession removes session`() {
        val session = service.createNewSession()
        service.deleteSession(session.id)
        assertNull(repo.getSessionById(session.id))
    }

    @Test
    fun `deleteSession re-parents children to grandparent`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val grandchild = service.createBranch(child.id)!!

        service.deleteSession(child.id)

        val updatedGrandchild = repo.getSessionById(grandchild.id)!!
        assertEquals(root.id, updatedGrandchild.parentId)
        assertEquals(1, updatedGrandchild.depth)
    }

    @Test
    fun `deleteSession makes children root when deleting root`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!

        service.deleteSession(root.id)

        val updatedChild = repo.getSessionById(child.id)!!
        assertNull(updatedChild.parentId)
        assertEquals(0, updatedChild.depth)
        assertTrue(updatedChild.isRoot)
    }

    // --- deleteSessionCascade ---

    @Test
    fun `deleteSessionCascade removes session and all descendants`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val grandchild = service.createBranch(child.id)!!

        service.deleteSessionCascade(child.id)

        assertNotNull(repo.getSessionById(root.id))
        assertNull(repo.getSessionById(child.id))
        assertNull(repo.getSessionById(grandchild.id))
    }

    // --- addMessage ---

    @Test
    fun `addMessage adds message to session`() {
        val session = service.createNewSession()
        val updated = service.addMessage(session.id, MessageRole.USER, "Hello")
        assertEquals(1, updated.messages.size)
        assertEquals("Hello", updated.messages[0].content)
    }

    @Test
    fun `addMessage persists to repository`() {
        val session = service.createNewSession()
        service.addMessage(session.id, MessageRole.USER, "Hello")
        val loaded = repo.getSessionById(session.id)!!
        assertEquals(1, loaded.messages.size)
    }

    // --- renameSession ---

    @Test
    fun `renameSession updates title`() {
        val session = service.createNewSession()
        val updated = service.renameSession(session.id, "New Title")
        assertEquals("New Title", updated?.title)
        assertEquals("New Title", repo.getSessionById(session.id)?.title)
    }

    @Test
    fun `renameSession returns null for nonexistent session`() {
        assertNull(service.renameSession("nonexistent", "title"))
    }

    // --- buildTree ---

    @Test
    fun `buildTree returns empty for no sessions`() {
        assertTrue(service.buildTree().isEmpty())
    }

    @Test
    fun `buildTree returns root sessions at top level`() {
        val s1 = service.createNewSession()
        val s2 = service.createNewSession()
        val tree = service.buildTree()
        assertEquals(2, tree.size)
        assertTrue(tree.any { it.id == s1.id })
        assertTrue(tree.any { it.id == s2.id })
    }

    @Test
    fun `buildTree nests children correctly`() {
        val root = service.createNewSession()
        val child = service.createBranch(root.id)!!
        val tree = service.buildTree()
        assertEquals(1, tree.size)
        assertEquals(1, tree[0].children.size)
        assertEquals(child.id, tree[0].children[0].id)
    }

    // --- token tracking ---

    @Test
    fun `addPlanningTokens accumulates correctly`() {
        val session = service.createNewSession()
        service.addPlanningTokens(session.id, 100, 50)
        service.addPlanningTokens(session.id, 200, 80)
        val loaded = repo.getSessionById(session.id)!!
        assertEquals(300, loaded.tokenUsage.planningInput)
        assertEquals(130, loaded.tokenUsage.planningOutput)
    }

    @Test
    fun `addChatTokens accumulates correctly`() {
        val session = service.createNewSession()
        service.addChatTokens(session.id, 500, 200)
        val loaded = repo.getSessionById(session.id)!!
        assertEquals(500, loaded.tokenUsage.chatInput)
        assertEquals(200, loaded.tokenUsage.chatOutput)
    }

    // --- context files ---

    @Test
    fun `addGlobalContextFile adds file`() {
        service.addGlobalContextFile("src/Main.kt")
        assertTrue(service.getGlobalContextFiles().contains("src/Main.kt"))
    }

    @Test
    fun `addGlobalContextFile does not duplicate`() {
        service.addGlobalContextFile("src/Main.kt")
        service.addGlobalContextFile("src/Main.kt")
        assertEquals(1, service.getGlobalContextFiles().size)
    }

    @Test
    fun `removeGlobalContextFile removes file`() {
        service.addGlobalContextFile("src/Main.kt")
        service.removeGlobalContextFile("src/Main.kt")
        assertFalse(service.getGlobalContextFiles().contains("src/Main.kt"))
    }

    @Test
    fun `addGlobalContextFile normalizes backslashes`() {
        service.addGlobalContextFile("src\\Main.kt")
        assertTrue(service.getGlobalContextFiles().contains("src/Main.kt"))
    }
}
