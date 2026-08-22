package com.maxvibes.plugin.clipboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тип и путь правки принимаются под несколькими именами — так же, как содержимое,
 * а отвергнутая запись называет конкретный дефект вместо общей фразы про формат.
 *
 * Агент, которому нужно было заменить обычный markdown-файл, перебрал `kind`,
 * `operation` и `filePath` и не применил ни одной правки: кодек читал только
 * канонические имена и отбрасывал запись, ничего не сообщая о причине.
 */
class JsonInteractionProtocolCodecEnvelopeTest {

    private val codec = JsonInteractionProtocolCodec()

    private fun decodeSingle(entry: String) =
        codec.decode("""{"message": "done", "modifications": [$entry]}""")

    @Test
    fun `type sent as kind is accepted`() {
        val response = decodeSingle(
            """{"kind": "REPLACE_FILE", "path": "file:docs/PLAN.md", "content": "# Plan"}"""
        )

        assertNotNull(response)
        assertEquals("REPLACE_FILE", response!!.modifications.single().type)
        assertTrue(response.malformedModifications.isEmpty())
    }

    @Test
    fun `type sent as operation is accepted`() {
        val response = decodeSingle(
            """{"operation": "REPLACE_FILE", "path": "file:docs/PLAN.md", "content": "# Plan"}"""
        )

        assertNotNull(response)
        assertEquals("REPLACE_FILE", response!!.modifications.single().type)
    }

    @Test
    fun `path sent as filePath is accepted`() {
        val response = decodeSingle(
            """{"type": "REPLACE_FILE", "filePath": "file:docs/PLAN.md", "content": "# Plan"}"""
        )

        assertNotNull(response)
        assertEquals("file:docs/PLAN.md", response!!.modifications.single().path)
    }

    @Test
    fun `path sent as targetPath is accepted`() {
        val response = decodeSingle(
            """{"type": "REPLACE_FILE", "targetPath": "file:docs/PLAN.md", "content": "# Plan"}"""
        )

        assertNotNull(response)
        assertEquals("file:docs/PLAN.md", response!!.modifications.single().path)
    }

    @Test
    fun `canonical type wins over a synonym`() {
        val response = decodeSingle(
            """{"type": "REPLACE_FILE", "kind": "BUILD", "path": "file:docs/PLAN.md", "content": "# Plan"}"""
        )

        assertNotNull(response)
        assertEquals("REPLACE_FILE", response!!.modifications.single().type)
    }

    @Test
    fun `an unknown name for type is reported as the canonical field missing`() {
        val response = decodeSingle(
            """{"action": "REPLACE_FILE", "path": "file:docs/PLAN.md", "content": "# Plan"}"""
        )

        assertNotNull(response)
        assertTrue(response!!.modifications.isEmpty())
        val entry = response.malformedModifications.single()
        assertTrue(entry.contains("нет поля type"), entry)
        assertTrue(entry.contains("action"), entry)
    }

    @Test
    fun `a blank path is reported under the name it arrived with`() {
        val response = decodeSingle(
            """{"type": "REPLACE_FILE", "filePath": "", "content": "# Plan"}"""
        )

        assertNotNull(response)
        assertTrue(response!!.modifications.isEmpty())
        val entry = response.malformedModifications.single()
        assertTrue(entry.contains("path пустой"), entry)
        assertTrue(entry.contains("filePath"), entry)
    }

    @Test
    fun `a code carrying operation without content names both the operation and the field`() {
        val response = decodeSingle(
            """{"kind": "REPLACE_FILE", "path": "file:docs/PLAN.md"}"""
        )

        assertNotNull(response)
        assertTrue(response!!.modifications.isEmpty())
        val entry = response.malformedModifications.single()
        assertTrue(entry.contains("REPLACE_FILE"), entry)
        assertTrue(entry.contains("content"), entry)
    }
}
