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
 *
 * Отдельная группа — склейка `elementPath` с путём файла. Такого поля в протоколе
 * нет, и без склейки операция над элементом приходила с адресом файла: REPLACE_ELEMENT
 * молча превращался в перезапись всего файла.
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

    @Test
    fun `an element path sent beside a file path is joined into one address`() {
        val response = decodeSingle(
            """{"kind": "REPLACE_ELEMENT", "path": "core/src/BattleScreen.kt",
                 "elementPath": "class[BattleScreen]/init[0]", "newText": "init { }"}"""
        )

        assertNotNull(response)
        assertEquals(
            "file:core/src/BattleScreen.kt/class[BattleScreen]/init[0]",
            response!!.modifications.single().path
        )
    }

    @Test
    fun `a path that already addresses an element wins over elementPath`() {
        val response = decodeSingle(
            """{"type": "REPLACE_ELEMENT", "path": "file:core/src/BattleScreen.kt/class[BattleScreen]/function[draw]",
                 "elementPath": "class[BattleScreen]/init[0]", "content": "fun draw() {}"}"""
        )

        assertNotNull(response)
        assertEquals(
            "file:core/src/BattleScreen.kt/class[BattleScreen]/function[draw]",
            response!!.modifications.single().path
        )
    }

    @Test
    fun `joining does not double the file prefix`() {
        val response = decodeSingle(
            """{"type": "REPLACE_ELEMENT", "path": "file:core/src/BattleScreen.kt",
                 "elementPath": "class[BattleScreen]/init", "content": "init { }"}"""
        )

        assertNotNull(response)
        assertEquals(
            "file:core/src/BattleScreen.kt/class[BattleScreen]/init",
            response!!.modifications.single().path
        )
    }

    @Test
    fun `stray slashes around the element path are trimmed`() {
        val response = decodeSingle(
            """{"type": "REPLACE_ELEMENT", "path": "core/src/BattleScreen.kt/",
                 "elementPath": "/class[BattleScreen]/init/", "content": "init { }"}"""
        )

        assertNotNull(response)
        assertEquals(
            "file:core/src/BattleScreen.kt/class[BattleScreen]/init",
            response!!.modifications.single().path
        )
    }

    @Test
    fun `an import fqn sent as import is accepted`() {
        val response = decodeSingle(
            """{"kind": "ADD_IMPORT", "path": "file:src/A.kt",
                 "import": "com.example.battle.BattleSimulationResult"}"""
        )

        assertNotNull(response)
        assertEquals(
            "com.example.battle.BattleSimulationResult",
            response!!.modifications.single().importPath
        )
    }
}
