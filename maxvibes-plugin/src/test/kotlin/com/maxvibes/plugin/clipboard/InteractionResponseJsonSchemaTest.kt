package com.maxvibes.plugin.clipboard

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InteractionResponseJsonSchemaTest {
    @Test
    fun `every object declares all properties required and forbids extras`() {
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    if ((element["type"] as? JsonPrimitive)?.content == "object") {
                        val properties = element.getValue("properties").jsonObject
                        val required = element.getValue("required").jsonArray.map { it.jsonPrimitive.content }
                        assertEquals(properties.keys, required.toSet())
                        assertEquals(required.size, required.toSet().size)
                        assertEquals(JsonPrimitive(false), element["additionalProperties"])
                    }
                    element.values.forEach { visit(it) }
                }

                is JsonArray -> element.forEach { visit(it) }
                else -> Unit
            }
        }
        visit(InteractionResponseJsonSchema.schema)
    }

    @Test
    fun `schema includes every public response channel`() {
        val properties = InteractionResponseJsonSchema.schema.getValue("properties").jsonObject
        assertEquals(
            setOf(
                "message", "reasoning", "requestedFiles", "requestedViews", "modifications",
                "commitMessage", "chatTitle", "commands", "checks", "questions", "plan", "diagram", "turnIntent"
            ),
            properties.keys
        )
    }

    @Test
    fun `schema shaped response with nullable modification fields decodes`() {
        val raw = """{
            "message":"Ready", "reasoning":null, "requestedFiles":[], "requestedViews":[],
            "modifications":[{"type":"REPLACE_ELEMENT","path":"file:A.kt/function[x]",
                "content":"fun x() = 1","elementKind":null,"position":null,
                "importPath":null,"newName":null,"destination":null}],
            "commitMessage":null,"chatTitle":null,"commands":[],"checks":[],"questions":[],
            "plan":null,"diagram":null,"turnIntent":"DONE"
        }"""
        val response = assertNotNull(JsonInteractionProtocolCodec().decode(raw))
        assertEquals("Ready", response.message)
        assertEquals("fun x() = 1", response.modifications.single().content)
        assertEquals("FILE", response.modifications.single().elementKind)
        assertEquals("LAST_CHILD", response.modifications.single().position)
        assertTrue(response.malformedModifications.isEmpty())
        assertEquals("DONE", response.turnIntent?.name)
    }
}
