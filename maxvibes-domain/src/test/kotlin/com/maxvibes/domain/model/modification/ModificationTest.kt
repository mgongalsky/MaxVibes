package com.maxvibes.domain.model.modification

import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModificationTest {

    @Test
    fun `CreateFile should have correct target path`() {
        val mod = Modification.CreateFile(
            targetPath = ElementPath.file("src/NewFile.kt"),
            content = "class NewClass"
        )

        assertEquals("file:src/NewFile.kt", mod.targetPath.value)
        assertEquals("class NewClass", mod.content)
    }

    @Test
    fun `CreateElement should have default position LAST_CHILD`() {
        val mod = Modification.CreateElement(
            targetPath = ElementPath("file:User.kt/class[User]"),
            elementKind = ElementKind.FUNCTION,
            content = "fun newFunc() {}"
        )

        assertEquals(InsertPosition.LAST_CHILD, mod.position)
    }

    @Test
    fun `ModificationResult Success should be successful`() {
        val mod = Modification.CreateFile(
            targetPath = ElementPath.file("Test.kt"),
            content = "class Test"
        )

        val result = ModificationResult.Success(
            modification = mod,
            affectedPath = ElementPath.file("Test.kt"),
            resultContent = "class Test"
        )

        assertTrue(result.success)
        assertEquals(mod, result.modification)
    }

    @Test
    fun `ModificationResult Failure should not be successful`() {
        val mod = Modification.DeleteFile(
            targetPath = ElementPath.file("NonExistent.kt")
        )

        val result = ModificationResult.Failure(
            modification = mod,
            error = ModificationError.FileNotFound("NonExistent.kt")
        )

        assertFalse(result.success)
        assertTrue(result.error.message.contains("NonExistent.kt"))
    }

    @Test
    fun `ModificationError types should have correct messages`() {
        val notFound = ModificationError.ElementNotFound(ElementPath("file:Test.kt/class[Missing]"))
        val fileNotFound = ModificationError.FileNotFound("Missing.kt")
        val parseError = ModificationError.ParseError("Unexpected token")
        val invalidOp = ModificationError.InvalidOperation("Cannot delete root")
        val ioError = ModificationError.IOError("Disk full")

        assertTrue(notFound.message.contains("class[Missing]"))
        assertTrue(fileNotFound.message.contains("Missing.kt"))
        assertTrue(parseError.message.contains("Unexpected token"))
        assertTrue(invalidOp.message.contains("Cannot delete root"))
        assertTrue(ioError.message.contains("Disk full"))
    }
}