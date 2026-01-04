// maxvibes-adapter-llm/src/test/kotlin/com/maxvibes/adapter/llm/agent/tools/CodeModificationToolsTest.kt
package com.maxvibes.adapter.llm.agent.tools

import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeModificationToolsTest {

    private lateinit var tools: CodeModificationTools

    @BeforeEach
    fun setup() {
        tools = CodeModificationTools()
    }

    @Test
    fun `createElement should create CreateElement modification`() {
        val result = tools.createElement(
            targetPath = "file:src/User.kt/class[User]",
            elementKind = "FUNCTION",
            content = "fun hello() = println(\"Hello\")",
            position = "LAST_CHILD"
        )

        assertTrue(result.startsWith("SUCCESS"))
        assertEquals(1, tools.modifications.size)

        val modification = tools.modifications.first()
        assertTrue(modification is Modification.CreateElement)

        val createMod = modification as Modification.CreateElement
        assertEquals("file:src/User.kt/class[User]", createMod.targetPath.value)
        assertEquals(ElementKind.FUNCTION, createMod.elementKind)
        assertEquals("fun hello() = println(\"Hello\")", createMod.content)
        assertEquals(InsertPosition.LAST_CHILD, createMod.position)
    }

    @Test
    fun `createElement with invalid elementKind should return error`() {
        val result = tools.createElement(
            targetPath = "file:src/User.kt",
            elementKind = "INVALID_KIND",
            content = "some content"
        )

        assertTrue(result.startsWith("ERROR"))
        assertEquals(0, tools.modifications.size)
    }

    @Test
    fun `replaceElement should create ReplaceElement modification`() {
        val result = tools.replaceElement(
            targetPath = "file:src/User.kt/class[User]/function[getName]",
            newContent = "fun getName(): String = name.uppercase()"
        )

        assertTrue(result.startsWith("SUCCESS"))
        assertEquals(1, tools.modifications.size)

        val modification = tools.modifications.first()
        assertTrue(modification is Modification.ReplaceElement)

        val replaceMod = modification as Modification.ReplaceElement
        assertEquals("file:src/User.kt/class[User]/function[getName]", replaceMod.targetPath.value)
        assertEquals("fun getName(): String = name.uppercase()", replaceMod.newContent)
    }

    @Test
    fun `deleteElement should create DeleteElement modification`() {
        val result = tools.deleteElement(
            targetPath = "file:src/User.kt/class[User]/function[oldMethod]"
        )

        assertTrue(result.startsWith("SUCCESS"))
        assertEquals(1, tools.modifications.size)

        val modification = tools.modifications.first()
        assertTrue(modification is Modification.DeleteElement)
        assertEquals("file:src/User.kt/class[User]/function[oldMethod]", modification.targetPath.value)
    }

    @Test
    fun `createFile should create CreateFile modification`() {
        val content = """
            package com.example
            
            class NewClass {
                fun doSomething() = Unit
            }
        """.trimIndent()

        val result = tools.createFile(
            filePath = "file:src/main/kotlin/com/example/NewClass.kt",
            content = content
        )

        assertTrue(result.startsWith("SUCCESS"))
        assertEquals(1, tools.modifications.size)

        val modification = tools.modifications.first()
        assertTrue(modification is Modification.CreateFile)

        val createFileMod = modification as Modification.CreateFile
        assertEquals("file:src/main/kotlin/com/example/NewClass.kt", createFileMod.targetPath.value)
        assertEquals(content, createFileMod.content)
    }

    @Test
    fun `replaceFile should create ReplaceFile modification`() {
        val newContent = "package com.example\n\nclass UpdatedClass"

        val result = tools.replaceFile(
            filePath = "file:src/main/kotlin/OldClass.kt",
            newContent = newContent
        )

        assertTrue(result.startsWith("SUCCESS"))
        assertEquals(1, tools.modifications.size)

        val modification = tools.modifications.first()
        assertTrue(modification is Modification.ReplaceFile)
    }

    @Test
    fun `deleteFile should create DeleteFile modification`() {
        val result = tools.deleteFile(
            filePath = "file:src/main/kotlin/OldClass.kt"
        )

        assertTrue(result.startsWith("SUCCESS"))
        assertEquals(1, tools.modifications.size)

        val modification = tools.modifications.first()
        assertTrue(modification is Modification.DeleteFile)
    }

    @Test
    fun `multiple modifications should accumulate`() {
        tools.createElement(
            targetPath = "file:src/User.kt/class[User]",
            elementKind = "PROPERTY",
            content = "val id: String = UUID.randomUUID().toString()"
        )

        tools.createElement(
            targetPath = "file:src/User.kt/class[User]",
            elementKind = "FUNCTION",
            content = "fun getId(): String = id"
        )

        tools.deleteElement(
            targetPath = "file:src/User.kt/class[User]/function[oldGetter]"
        )

        assertEquals(3, tools.modifications.size)
    }

    @Test
    fun `clearModifications should remove all modifications`() {
        tools.createElement(
            targetPath = "file:src/User.kt",
            elementKind = "CLASS",
            content = "class NewClass"
        )

        tools.createElement(
            targetPath = "file:src/User.kt",
            elementKind = "FUNCTION",
            content = "fun test()"
        )

        assertEquals(2, tools.modifications.size)

        tools.clearModifications()

        assertEquals(0, tools.modifications.size)
    }

    @Test
    fun `finishModifications should return summary`() {
        tools.createElement(
            targetPath = "file:src/User.kt/class[User]",
            elementKind = "FUNCTION",
            content = "fun toString(): String = \"User\""
        )

        tools.replaceElement(
            targetPath = "file:src/User.kt/class[User]/property[name]",
            newContent = "val name: String = \"default\""
        )

        val summary = tools.finishModifications()

        assertTrue(summary.contains("Modifications Summary"))
        assertTrue(summary.contains("Total modifications: 2"))
        assertTrue(summary.contains("CREATE FUNCTION"))
        assertTrue(summary.contains("REPLACE"))
    }

    @Test
    fun `createElement should support all element kinds`() {
        val kinds = listOf("CLASS", "INTERFACE", "OBJECT", "ENUM", "FUNCTION", "PROPERTY", "CONSTRUCTOR")

        kinds.forEach { kind ->
            tools.clearModifications()
            val result = tools.createElement(
                targetPath = "file:src/Test.kt",
                elementKind = kind,
                content = "// content for $kind"
            )
            assertTrue(result.startsWith("SUCCESS"), "Failed for kind: $kind")
        }
    }

    @Test
    fun `createElement should support all insert positions`() {
        val positions = listOf("FIRST_CHILD", "LAST_CHILD", "BEFORE", "AFTER")

        positions.forEach { position ->
            tools.clearModifications()
            val result = tools.createElement(
                targetPath = "file:src/Test.kt",
                elementKind = "FUNCTION",
                content = "fun test()",
                position = position
            )
            assertTrue(result.startsWith("SUCCESS"), "Failed for position: $position")

            val mod = tools.modifications.first() as Modification.CreateElement
            assertEquals(InsertPosition.valueOf(position), mod.position)
        }
    }

    @Test
    fun `content should be trimmed`() {
        tools.createElement(
            targetPath = "file:src/Test.kt",
            elementKind = "FUNCTION",
            content = "   fun test() { }   \n\n"
        )

        val mod = tools.modifications.first() as Modification.CreateElement
        assertEquals("fun test() { }", mod.content)
    }
}