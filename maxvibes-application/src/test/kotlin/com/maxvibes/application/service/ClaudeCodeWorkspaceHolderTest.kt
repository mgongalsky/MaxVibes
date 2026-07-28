package com.maxvibes.application.service

import com.maxvibes.application.testsupport.FakeProjectContextPort
import com.maxvibes.application.testsupport.FakePromptPort
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClaudeCodeWorkspaceHolderTest {

    private fun newState(message: String = "msg"): ClipboardSessionState = ClipboardSessionState(
        currentMessage = message,
        projectContext = FakeProjectContextPort.defaultContext(),
        dialogHistory = mutableListOf(),
        prompts = runBlocking { FakePromptPort().getPrompts() },
        allGatheredFiles = mutableMapOf(),
        planOnly = false
    )

    @Test
    fun `fresh holder owns nothing`() {
        val holder = ClaudeCodeWorkspaceHolder()
        assertNull(holder.state)
        assertNull(holder.owner)
        assertFalse(holder.isOwnedBy("s1"))
    }

    @Test
    fun `install sets state and owner atomically`() {
        val holder = ClaudeCodeWorkspaceHolder()
        val state = newState()
        holder.install("s1", state)
        assertSame(state, holder.state)
        assertTrue(holder.isOwnedBy("s1"))
        assertFalse(holder.isOwnedBy("s2"))
    }

    @Test
    fun `reinstall by another session transfers ownership`() {
        val holder = ClaudeCodeWorkspaceHolder()
        holder.install("s1", newState("first"))
        val second = newState("second")
        holder.install("s2", second)
        assertTrue(holder.isOwnedBy("s2"))
        assertFalse(holder.isOwnedBy("s1"))
        assertSame(second, holder.state)
    }

    @Test
    fun `clear drops state and owner together`() {
        val holder = ClaudeCodeWorkspaceHolder()
        holder.install("s1", newState())
        holder.clear()
        assertNull(holder.state)
        assertNull(holder.owner)
        assertFalse(holder.isOwnedBy("s1"))
    }
}
