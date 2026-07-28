package com.maxvibes.application.service

import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.interaction.InteractionCommand
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the behaviour of [ProtocolConverter] exactly as it existed in the
 * duplicated private convert* functions of ClaudeCodeInteractionService and
 * ClipboardInteractionService (verified char-identical before extraction).
 */
class ProtocolConverterTest {

    // ── convertModification: guard clauses ─────────────────────────────────

    @Test
    fun `blank type returns null`() {
        assertNull(ProtocolConverter.convertModification(InteractionModification(type = "", path = "file:A.kt")))
    }

    @Test
    fun `blank path returns null`() {
        assertNull(ProtocolConverter.convertModification(InteractionModification(type = "CREATE_FILE", path = "")))
    }

    @Test
    fun `unknown type returns null`() {
        assertNull(ProtocolConverter.convertModification(InteractionModification(type = "EXPLODE", path = "file:A.kt")))
    }

    // ── convertModification: type mapping ──────────────────────────────────

    @Test
    fun `create file maps path and content`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(type = "CREATE_FILE", path = "file:src/A.kt", content = "class A")
        )
        result as Modification.CreateFile
        assertEquals("file:src/A.kt", result.targetPath.value)
        assertEquals("class A", result.content)
    }

    @Test
    fun `replace file maps content`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(type = "REPLACE_FILE", path = "file:src/A.kt", content = "class B")
        )
        result as Modification.ReplaceFile
        assertEquals("class B", result.newContent)
    }

    @Test
    fun `delete file maps`() {
        assertTrue(
            ProtocolConverter.convertModification(
                InteractionModification(type = "DELETE_FILE", path = "file:src/A.kt")
            ) is Modification.DeleteFile
        )
    }

    @Test
    fun `type matching is case insensitive`() {
        assertTrue(
            ProtocolConverter.convertModification(
                InteractionModification(type = "replace_element", path = "file:A.kt/class[A]", content = "x")
            ) is Modification.ReplaceElement
        )
    }

    @Test
    fun `create element maps kind and position`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(
                type = "CREATE_ELEMENT", path = "file:A.kt/class[A]",
                content = "fun x() {}", elementKind = "function", position = "after"
            )
        )
        result as Modification.CreateElement
        assertEquals(ElementKind.FUNCTION, result.elementKind)
        assertEquals(InsertPosition.AFTER, result.position)
    }

    @Test
    fun `invalid kind falls back to FILE and invalid position to LAST_CHILD`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(
                type = "CREATE_ELEMENT", path = "file:A.kt/class[A]",
                content = "fun x() {}", elementKind = "WIDGET", position = "SOMEWHERE"
            )
        )
        result as Modification.CreateElement
        assertEquals(ElementKind.FILE, result.elementKind)
        assertEquals(InsertPosition.LAST_CHILD, result.position)
    }

    @Test
    fun `delete element maps`() {
        assertTrue(
            ProtocolConverter.convertModification(
                InteractionModification(type = "DELETE_ELEMENT", path = "file:A.kt/function[x]")
            ) is Modification.DeleteElement
        )
    }

    // ── convertModification: imports ───────────────────────────────────────

    @Test
    fun `add import uses importPath field`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(type = "ADD_IMPORT", path = "file:A.kt", importPath = "com.example.Dto")
        )
        result as Modification.AddImport
        assertEquals("com.example.Dto", result.importPath)
    }

    @Test
    fun `add import falls back to content with import prefix stripped`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(type = "ADD_IMPORT", path = "file:A.kt", content = "import com.example.Dto")
        )
        result as Modification.AddImport
        assertEquals("com.example.Dto", result.importPath)
    }

    @Test
    fun `add import with no fqn returns null`() {
        assertNull(
            ProtocolConverter.convertModification(
                InteractionModification(type = "ADD_IMPORT", path = "file:A.kt")
            )
        )
    }

    @Test
    fun `remove import uses importPath with content fallback`() {
        val direct = ProtocolConverter.convertModification(
            InteractionModification(type = "REMOVE_IMPORT", path = "file:A.kt", importPath = "com.example.Old")
        ) as Modification.RemoveImport
        assertEquals("com.example.Old", direct.importPath)

        val fallback = ProtocolConverter.convertModification(
            InteractionModification(type = "REMOVE_IMPORT", path = "file:A.kt", content = "import com.example.Old")
        ) as Modification.RemoveImport
        assertEquals("com.example.Old", fallback.importPath)

        assertNull(
            ProtocolConverter.convertModification(
                InteractionModification(type = "REMOVE_IMPORT", path = "file:A.kt")
            )
        )
    }

    // ── convertModification: refactorings ──────────────────────────────────

    @Test
    fun `rename element trims name and requires it non-blank`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(type = "RENAME_ELEMENT", path = "file:A.kt/function[x]", newName = " newX ")
        )
        result as Modification.RenameElement
        assertEquals("newX", result.newName)

        assertNull(
            ProtocolConverter.convertModification(
                InteractionModification(type = "RENAME_ELEMENT", path = "file:A.kt/function[x]", newName = "  ")
            )
        )
    }

    @Test
    fun `safe delete maps`() {
        assertTrue(
            ProtocolConverter.convertModification(
                InteractionModification(type = "SAFE_DELETE", path = "file:A.kt/function[x]")
            ) is Modification.SafeDelete
        )
    }

    @Test
    fun `move element trims destination and requires it non-blank`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(type = "MOVE_ELEMENT", path = "file:A.kt", destination = " src/util ")
        )
        result as Modification.MoveElement
        assertEquals("src/util", result.destination)

        assertNull(
            ProtocolConverter.convertModification(
                InteractionModification(type = "MOVE_ELEMENT", path = "file:A.kt", destination = "")
            )
        )
    }

    // ── convertCommand ─────────────────────────────────────────────────────

    @Test
    fun `blank command returns null`() {
        assertNull(ProtocolConverter.convertCommand(InteractionCommand(command = "  ")))
    }

    @Test
    fun `command maps with reason and timeout`() {
        val result = ProtocolConverter.convertCommand(
            InteractionCommand(command = "git status", reason = "check state", timeoutSec = 60)
        )!!
        assertEquals("git status", result.command)
        assertEquals("check state", result.reason)
        assertEquals(60, result.timeoutSec)
    }

    @Test
    fun `blank reason becomes null`() {
        assertNull(ProtocolConverter.convertCommand(InteractionCommand(command = "git status", reason = " "))!!.reason)
    }

    @Test
    fun `timeout is clamped to 1-3600`() {
        assertEquals(
            1,
            ProtocolConverter.convertCommand(InteractionCommand(command = "x", timeoutSec = 0))!!.timeoutSec
        )
        assertEquals(
            3600,
            ProtocolConverter.convertCommand(InteractionCommand(command = "x", timeoutSec = 99999))!!.timeoutSec
        )
    }
}
