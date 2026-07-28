package com.maxvibes.application.service

import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.InteractionModification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingModificationsStoreTest {

    private val store = PendingModificationsStore()

    private fun mod(path: String = "file:A.kt") =
        InteractionModification(type = "REPLACE_FILE", path = path, content = "x")

    @Test
    fun `hold makes set visible to owner session only`() {
        store.hold("s1", listOf(mod()))

        assertTrue(store.hasPendingFor("s1"))
        assertFalse(store.hasPendingFor("s2"))
    }

    @Test
    fun `take returns held set once and clears`() {
        store.hold("s1", listOf(mod()), listOf(CommandRequest("git status")), "feat: x")

        val taken = store.take("s1")

        assertNotNull(taken)
        assertEquals(1, taken!!.modifications.size)
        assertEquals("git status", taken.commands.single().command)
        assertEquals("feat: x", taken.commitMessage)
        assertFalse(store.hasPendingFor("s1"))
        assertNull(store.take("s1"))
    }

    @Test
    fun `take from foreign session returns null and keeps owner set intact`() {
        store.hold("s1", listOf(mod()))

        assertNull(store.take("s2"))
        assertTrue(store.hasPendingFor("s1"))
    }

    @Test
    fun `empty modification set is never pending`() {
        store.hold("s1", emptyList())

        assertFalse(store.hasPendingFor("s1"))
        assertNull(store.take("s1"))
    }

    @Test
    fun `new hold replaces previous set including owner`() {
        store.hold("s1", listOf(mod("file:A.kt")))
        store.hold("s2", listOf(mod("file:B.kt")))

        assertFalse(store.hasPendingFor("s1"))
        assertEquals("file:B.kt", store.take("s2")!!.modifications.single().path)
    }

    @Test
    fun `clear drops everything`() {
        store.hold("s1", listOf(mod()))

        store.clear()

        assertFalse(store.hasPendingFor("s1"))
        assertNull(store.take("s1"))
    }
}
