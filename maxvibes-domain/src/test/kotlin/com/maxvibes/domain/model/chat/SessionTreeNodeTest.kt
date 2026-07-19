package com.maxvibes.domain.model.chat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionTreeNodeTest {

    private fun session(id: String, title: String = "Session $id", depth: Int = 0) =
        ChatSession(id = id, title = title, depth = depth)

    @Test
    fun `leaf node has no children`() {
        val node = SessionTreeNode(session("1"))
        assertFalse(node.hasChildren)
        assertTrue(node.children.isEmpty())
    }

    @Test
    fun `node with children reports hasChildren`() {
        val child = SessionTreeNode(session("2"))
        val parent = SessionTreeNode(session("1"), listOf(child))
        assertTrue(parent.hasChildren)
        assertEquals(1, parent.children.size)
    }

    @Test
    fun `id delegates to session id`() {
        val node = SessionTreeNode(session("abc"))
        assertEquals("abc", node.id)
    }

    @Test
    fun `title delegates to session title`() {
        val node = SessionTreeNode(session("1", title = "My Chat"))
        assertEquals("My Chat", node.title)
    }

    @Test
    fun `depth delegates to session depth`() {
        val node = SessionTreeNode(session("1", depth = 3))
        assertEquals(3, node.depth)
    }

    @Test
    fun `withChildren replaces children`() {
        val original = SessionTreeNode(session("1"))
        val child = SessionTreeNode(session("2"))
        val updated = original.withChildren(listOf(child))
        assertTrue(original.children.isEmpty())
        assertEquals(1, updated.children.size)
    }

    @Test
    fun `nested tree structure`() {
        val grandchild = SessionTreeNode(session("3", depth = 2))
        val child = SessionTreeNode(session("2", depth = 1), listOf(grandchild))
        val root = SessionTreeNode(session("1", depth = 0), listOf(child))

        assertTrue(root.hasChildren)
        assertTrue(root.children[0].hasChildren)
        assertFalse(root.children[0].children[0].hasChildren)
        assertEquals(0, root.depth)
        assertEquals(1, root.children[0].depth)
        assertEquals(2, root.children[0].children[0].depth)
    }
}
