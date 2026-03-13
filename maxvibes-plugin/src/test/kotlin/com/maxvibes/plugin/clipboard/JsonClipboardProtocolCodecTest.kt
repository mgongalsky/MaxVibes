package com.maxvibes.plugin.clipboard

import com.maxvibes.application.port.output.ClipboardRequestSchema
import com.maxvibes.domain.model.interaction.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [JsonClipboardProtocolCodec].
 *
 * No IDE, no mocks — just pure encode/decode logic.
 * Run via `./gradlew test` or the IntelliJ IDEA runner.
 */
class JsonClipboardProtocolCodecTest {

    private val codec = JsonClipboardProtocolCodec()

    // ── Helpers ───────────────────────────────────────────────────────

    /** Builds a minimal [ClipboardRequest] with sensible defaults for encode tests. */
    private fun minimalRequest(
        task: String = "do something",
        systemInstruction: String = "",
        planOnly: Boolean = false,
        fileTree: String = "",
        freshFiles: Map<String, String> = emptyMap(),
        previouslyGatheredPaths: List<String> = emptyList(),
        chatHistory: List<ClipboardHistoryEntry> = emptyList(),
        attachedContext: String? = null,
        ideErrors: String? = null
    ) = ClipboardRequest(
        phase = ClipboardPhase.CHAT,
        task = task,
        projectName = "TestProject",
        systemInstruction = systemInstruction,
        planOnly = planOnly,
        fileTree = fileTree,
        freshFiles = freshFiles,
        previouslyGatheredPaths = previouslyGatheredPaths,
        chatHistory = chatHistory,
        attachedContext = attachedContext,
        ideErrors = ideErrors
    )

    // ── encode() ──────────────────────────────────────────────────────

    @Test
    fun `encode contains _protocol marker`() {
        val result = codec.encode(minimalRequest())
        assertTrue(
            result.contains("\"${ClipboardRequestSchema.META_PROTOCOL}\""),
            "Expected _protocol key in output"
        )
        assertTrue(
            result.contains(ClipboardRequestSchema.PROTOCOL_MARKER),
            "Expected protocol marker value in output"
        )
    }

    @Test
    fun `encode contains _responseFormat hint`() {
        val result = codec.encode(minimalRequest())
        assertTrue(
            result.contains("\"${ClipboardRequestSchema.META_RESPONSE_FORMAT}\""),
            "Expected _responseFormat key in output"
        )
    }

    @Test
    fun `encode planOnly=true adds field, planOnly=false omits it`() {
        val withPlan = codec.encode(minimalRequest(planOnly = true))
        assertTrue(
            withPlan.contains("\"${ClipboardRequestSchema.FIELD_PLAN_ONLY}\""),
            "planOnly=true should produce planOnly field"
        )

        val withoutPlan = codec.encode(minimalRequest(planOnly = false))
        assertFalse(
            withoutPlan.contains("\"${ClipboardRequestSchema.FIELD_PLAN_ONLY}\""),
            "planOnly=false should omit planOnly field"
        )
    }

    @Test
    fun `encode empty fileTree omits field`() {
        val result = codec.encode(minimalRequest(fileTree = ""))
        assertFalse(
            result.contains("\"${ClipboardRequestSchema.FIELD_FILE_TREE}\""),
            "Blank fileTree should be omitted"
        )
    }

    @Test
    fun `encode freshFiles serialized under files key`() {
        val result = codec.encode(
            minimalRequest(
                freshFiles = mapOf("src/Foo.kt" to "class Foo", "src/Bar.kt" to "class Bar")
            )
        )
        assertTrue(
            result.contains("\"${ClipboardRequestSchema.FIELD_FILES}\""),
            "Expected 'files' key"
        )
        assertTrue(result.contains("src/Foo.kt"), "Expected file path in output")
        assertTrue(result.contains("class Foo"), "Expected file content in output")
    }

    @Test
    fun `encode chatHistory serialized with role and content`() {
        val history = listOf(
            ClipboardHistoryEntry(role = "user", content = "hello"),
            ClipboardHistoryEntry(role = "assistant", content = "world")
        )
        val result = codec.encode(minimalRequest(chatHistory = history))

        assertTrue(result.contains("\"${ClipboardRequestSchema.FIELD_CHAT_HISTORY}\""))
        assertTrue(result.contains("\"${ClipboardRequestSchema.HISTORY_ROLE}\""))
        assertTrue(result.contains("\"${ClipboardRequestSchema.HISTORY_CONTENT}\""))
        assertTrue(result.contains("\"user\""))
        assertTrue(result.contains("\"assistant\""))
        assertTrue(result.contains("\"hello\""))
        assertTrue(result.contains("\"world\""))
    }

    @Test
    fun `encode attachedContext goes into errorTrace field`() {
        val result = codec.encode(minimalRequest(attachedContext = "java.lang.NullPointerException\n\tat Foo.kt:42"))
        assertTrue(
            result.contains("\"${ClipboardRequestSchema.FIELD_ERROR_TRACE}\""),
            "Expected errorTrace key in output"
        )
        assertTrue(result.contains("NullPointerException"))
    }

    @Test
    fun `encode ideErrors goes into ideErrors field`() {
        val result = codec.encode(minimalRequest(ideErrors = "Unresolved reference: bar"))
        assertTrue(
            result.contains("\"${ClipboardRequestSchema.FIELD_IDE_ERRORS}\""),
            "Expected ideErrors key in output"
        )
        assertTrue(result.contains("Unresolved reference: bar"))
    }

    // ── decode() ──────────────────────────────────────────────────────

    @Test
    fun `decode raw JSON object returns message`() {
        val response = codec.decode("""{"message": "hello"}""")
        assertNotNull(response)
        assertEquals("hello", response!!.message)
    }

    @Test
    fun `decode JSON in json fenced block`() {
        val input = "```json\n{\"message\": \"from json block\"}\n```"
        val response = codec.decode(input)
        assertNotNull(response)
        assertEquals("from json block", response!!.message)
    }

    @Test
    fun `decode JSON in plain code block fallback`() {
        val input = "```\n{\"message\": \"from code block\"}\n```"
        val response = codec.decode(input)
        assertNotNull(response)
        assertEquals("from code block", response!!.message)
    }

    @Test
    fun `decode plain text without JSON returns ClipboardResponse with message`() {
        val response = codec.decode("This is just a plain text reply")
        assertNotNull(response)
        assertEquals("This is just a plain text reply", response!!.message)
    }

    @Test
    fun `decode empty or blank string returns null`() {
        assertNull(codec.decode(""))
        assertNull(codec.decode("   "))
    }

    @Test
    fun `decode JSON in fenced block with surrounding text fills blank message from surroundings`() {
        // LLM puts explanation outside the JSON block, message field inside is blank
        val input = "Here is my analysis:\n```json\n{\"message\": \"\", \"requestedFiles\": []}\n```\nHope this helps!"
        val response = codec.decode(input)
        assertNotNull(response)
        // Surrounding prose should replace the blank message field
        assertTrue(
            response!!.message.isNotBlank(),
            "Expected surrounding text to fill in blank message"
        )
    }

    @Test
    fun `decode requestedFiles parses as list`() {
        val input = """{"message": "ok", "requestedFiles": ["a/B.kt", "c/D.kt"]}"""
        val response = codec.decode(input)
        assertNotNull(response)
        assertEquals(listOf("a/B.kt", "c/D.kt"), response!!.requestedFiles)
    }

    @Test
    fun `decode modifications with all fields produces correct ClipboardModification`() {
        val input = """
        {
            "message": "done",
            "modifications": [{
                "type": "REPLACE_ELEMENT",
                "path": "file:src/Foo.kt/class[Foo]/function[bar]",
                "content": "fun bar() {}",
                "elementKind": "FUNCTION",
                "position": "LAST_CHILD",
                "importPath": "com.example.Baz"
            }]
        }
        """.trimIndent()
        val response = codec.decode(input)
        assertNotNull(response)
        assertEquals(1, response!!.modifications.size)

        val mod = response.modifications[0]
        assertEquals("REPLACE_ELEMENT", mod.type)
        assertEquals("file:src/Foo.kt/class[Foo]/function[bar]", mod.path)
        assertEquals("fun bar() {}", mod.content)
        assertEquals("FUNCTION", mod.elementKind)
        assertEquals("LAST_CHILD", mod.position)
        assertEquals("com.example.Baz", mod.importPath)
    }

    @Test
    fun `decode modifications without elementKind defaults to FILE`() {
        val input = """
        {
            "message": "done",
            "modifications": [{
                "type": "CREATE_FILE",
                "path": "src/New.kt",
                "content": "class New"
            }]
        }
        """.trimIndent()
        val response = codec.decode(input)
        assertNotNull(response)
        assertEquals(
            ClipboardRequestSchema.DEFAULT_ELEMENT_KIND,
            response!!.modifications[0].elementKind,
            "Missing elementKind should default to ${ClipboardRequestSchema.DEFAULT_ELEMENT_KIND}"
        )
    }

    @Test
    fun `decode commitMessage field is populated`() {
        val input = """{"message": "done", "commitMessage": "feat: add something"}"""
        val response = codec.decode(input)
        assertNotNull(response)
        assertEquals("feat: add something", response!!.commitMessage)
    }

    @Test
    fun `decode invalid JSON returns null`() {
        // No recognized indicator keys → findEmbeddedJson returns null
        // lenientJson parse still fails on this garbage
        val response = codec.decode("{not: valid !!! json *** at all}")
        assertNull(response, "Completely invalid JSON should decode to null")
    }

    @Test
    fun `decode reasoning field is populated`() {
        val input = """{"message": "ok", "reasoning": "because of X"}"""
        val response = codec.decode(input)
        assertNotNull(response)
        assertEquals("because of X", response!!.reasoning)
    }

    // ── findEmbeddedJson() ────────────────────────────────────────────

    @Test
    fun `findEmbeddedJson extracts JSON from surrounding prose`() {
        val text = "Some preamble\n{\"message\": \"hello\", \"requestedFiles\": []}\nSome epilogue"
        val result = codec.findEmbeddedJson(text)
        assertNotNull(result, "Expected embedded JSON to be found")
        assertTrue(result!!.startsWith("{"), "Result should start with '{'")
        assertTrue(result.contains("\"message\""), "Result should contain message key")
    }

    @Test
    fun `findEmbeddedJson returns null when no JSON indicators present`() {
        val result = codec.findEmbeddedJson("just plain text with no braces at all")
        assertNull(result, "Expected null when no JSON indicators are present")
    }
}
