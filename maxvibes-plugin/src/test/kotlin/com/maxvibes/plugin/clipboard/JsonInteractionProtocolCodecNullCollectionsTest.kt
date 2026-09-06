package com.maxvibes.plugin.clipboard

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonInteractionProtocolCodecNullCollectionsTest {
    private val codec = JsonInteractionProtocolCodec()

    @Test
    fun `null optional collections preserve message and modification`() {
        for (field in listOf("requestedFiles", "requestedViews", "commands", "checks", "questions")) {
            val raw = buildJsonObject {
                put("message", "Изменение готово")
                put(field, JsonNull)
                putJsonArray("modifications") {
                    addJsonObject {
                        put("type", "REPLACE_ELEMENT")
                        put("path", "file:src/A.kt/class[A]/function[x]")
                        put("replacement", "fun x() = 1")
                    }
                }
            }.toString()
            val response = assertNotNull(codec.decode(raw), "null in $field discarded the response")
            assertEquals("Изменение готово", response.message, field)
            assertEquals("fun x() = 1", response.modifications.single().content, field)
            assertTrue(response.malformedModifications.isEmpty(), field)
        }
    }

    @Test
    fun `null modifications means no modifications`() {
        val response = assertNotNull(codec.decode("""{"message":"Ответ без правок","modifications":null}"""))
        assertEquals("Ответ без правок", response.message)
        assertTrue(response.modifications.isEmpty())
        assertTrue(response.malformedModifications.isEmpty())
    }

    @Test
    fun `null question options preserve the question`() {
        val response = assertNotNull(
            codec.decode(
                """{"message":"Уточнение","questions":[{"id":"q1","question":"Какой вариант?","options":null}]}"""
            )
        )
        assertEquals("Какой вариант?", response.questions.single().question)
        assertTrue(response.questions.single().options.isEmpty())
    }
}
