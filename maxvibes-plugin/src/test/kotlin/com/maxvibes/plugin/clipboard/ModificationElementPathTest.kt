package com.maxvibes.plugin.clipboard

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModificationElementPathTest {
    private val codec = JsonInteractionProtocolCodec()
    private val file = "core/src/com/unciv/ui/battlescreen/BattleScreen.kt"

    private fun decode(path: String, elementPath: String) = assertNotNull(
        codec.decode(
            buildJsonObject {
                put("message", "Keep this message")
                putJsonArray("modifications") {
                    addJsonObject {
                        put("type", "CREATE_ELEMENT")
                        put("path", path)
                        put("elementPath", elementPath)
                        put("elementKind", "FUNCTION")
                        put("content", "fun added() {}")
                    }
                }
            }.toString()
        )
    )

    @Test
    fun `complete element path does not duplicate the file`() {
        val target = "file:$file/class[BattleScreen]"
        for (path in listOf(file, "file:$file")) {
            val response = decode(path, target)
            assertEquals(target, response.modifications.single().path)
            assertTrue(response.malformedModifications.isEmpty())
        }
    }

    @Test
    fun `relative element path is appended once`() {
        for (suffix in listOf("class[BattleScreen]", "/class[BattleScreen]/")) {
            assertEquals("file:$file/class[BattleScreen]", decode(file, suffix).modifications.single().path)
        }
    }

    @Test
    fun `complete path without file prefix is accepted`() {
        assertEquals(
            "file:$file/class[BattleScreen]",
            decode(file, "$file/class[BattleScreen]").modifications.single().path
        )
    }

    @Test
    fun `explicit element target takes precedence`() {
        val target = "file:$file/class[BattleScreen]"
        assertEquals(target, decode(target, "class[Other]").modifications.single().path)
    }

    @Test
    fun `conflicting file is diagnosed without losing the message`() {
        val response = decode(file, "file:src/Other.kt/class[Other]")
        assertEquals("Keep this message", response.message)
        assertTrue(response.modifications.isEmpty())
        assertTrue(response.malformedModifications.single().contains("different files"))
    }
}
