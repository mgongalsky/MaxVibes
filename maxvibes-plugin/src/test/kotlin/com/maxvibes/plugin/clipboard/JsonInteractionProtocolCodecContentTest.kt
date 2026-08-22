package com.maxvibes.plugin.clipboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Содержимое правки принимается под несколькими именами, а операция без кода
 * отвергается явно, а не превращается в замену на пустоту.
 *
 * Codex-семейство регулярно шлёт тело функции под ключом `replacement`: раньше оно
 * терялось молча, и PSI получал REPLACE_ELEMENT с пустой строкой.
 */
class JsonInteractionProtocolCodecContentTest {

    private val codec = JsonInteractionProtocolCodec()

    @Test
    fun `content sent as replacement is not lost`() {
        val response = codec.decode(
            """
            {
                "message": "done",
                "modifications": [{
                    "type": "REPLACE_ELEMENT",
                    "path": "file:A.kt/class[A]/function[x]",
                    "replacement": "fun x() = 1"
                }]
            }
            """.trimIndent()
        )

        assertNotNull(response)
        assertEquals("fun x() = 1", response!!.modifications.single().content)
    }

    @Test
    fun `canonical content wins over a synonym`() {
        val response = codec.decode(
            """
            {
                "message": "done",
                "modifications": [{
                    "type": "REPLACE_ELEMENT",
                    "path": "file:A.kt/class[A]/function[x]",
                    "content": "fun x() = 1",
                    "replacement": "fun x() = 2"
                }]
            }
            """.trimIndent()
        )

        assertNotNull(response)
        assertEquals("fun x() = 1", response!!.modifications.single().content)
    }

    @Test
    fun `replace element without any content is reported instead of reaching PSI`() {
        val response = codec.decode(
            """
            {
                "message": "done",
                "modifications": [{
                    "type": "REPLACE_ELEMENT",
                    "path": "file:A.kt/class[A]/function[x]"
                }]
            }
            """.trimIndent()
        )

        assertNotNull(response)
        assertTrue(response!!.modifications.isEmpty(), "An empty replacement must never reach PSI")
        assertEquals(1, response.malformedModifications.size)
    }

    @Test
    fun `add import still works without any content`() {
        val response = codec.decode(
            """
            {
                "message": "done",
                "modifications": [{
                    "type": "ADD_IMPORT",
                    "path": "file:A.kt",
                    "importPath": "com.example.Foo"
                }]
            }
            """.trimIndent()
        )

        assertNotNull(response)
        assertEquals("com.example.Foo", response!!.modifications.single().importPath)
    }
}
