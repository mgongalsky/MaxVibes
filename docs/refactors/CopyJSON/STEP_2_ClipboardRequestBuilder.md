# Step 2: Создать ClipboardRequestBuilder

## Контекст

После Step 1 `ClipboardSessionState` доступен как `internal` класс в пакете `com.maxvibes.application.service`.Логика сборки `ClipboardRequest` сейчас захардкожена внутри приватного метода `generateAndCopyJson()` в `ClipboardInteractionService`.Задача этого шага — вынести эту логику в чистый, unit - тестируемый объект .

## Задача

Создать `ClipboardRequestBuilder` — pure object без I / O и без зависимостей на IntelliJ SDK .

## Что делать

### 1.Создать файл `ClipboardRequestBuilder.kt`

**Путь * *: `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardRequestBuilder.kt`

```kotlin
package com.maxvibes.application.service

import com . maxvibes . application . port . output . ChatRole
        import com . maxvibes . domain . model . interaction . *

        /**
         * Pure builder for [ClipboardRequest].
         *
         * Contains the complete token-saving policy and field-population logic.
         * Zero I/O, zero IntelliJ SDK dependencies — directly unit-testable via Gradle.
         *
         * Single source of truth for how a [ClipboardRequest] is assembled;
         * both the Generate and Copy JSON flows delegate here via
         * [ClipboardInteractionService.generateAndCopyJson].
         */
        internal object ClipboardRequestBuilder {

    /**
     * Builds a [ClipboardRequest] from the current session state and freshly gathered files.
     *
     * ## Token-saving policy (Minimal mode)
     * When [isFirstMessage] is false AND [addHistory] is false, the request is minimal:
     * only the current user message, fresh files, and IDE errors are included.
     * Heavy context fields (systemInstruction, fileTree, chatHistory) are left blank
     * so [JsonClipboardProtocolCodec] omits them from the JSON output.
     *
     * @param state           In-memory session state accumulated so far.
     * @param freshFiles      Files gathered in this turn (path → content).
     * @param isFirstMessage  True for the very first message in a session.
     * @param addHistory      When true, full context and previously gathered paths are included.
     * @param planOnlySuffix  Optional suffix appended to the system prompt in plan-only mode.
     *                        Supplied by [ClipboardInteractionService] to avoid coupling the
     *                        builder to the prompt string constant.
     */
    fun build(
        state: ClipboardSessionState,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        addHistory: Boolean = false,
        planOnlySuffix: String = ""
    ): ClipboardRequest {
        // Minimal-mode: LLM already has all context in its chat window
        val isMinimal = !isFirstMessage && !addHistory
        val previousPaths: List<String> =
            if (addHistory) state.allGatheredFiles.keys.toList() else emptyList()

        // In minimal mode carry only the latest user message
        val taskContent = if (isMinimal) {
            state.dialogHistory.lastOrNull { it.role == ChatRole.USER }?.content
                ?: state.currentMessage
        } else {
            state.currentMessage
        }

        // System prompt: omitted in minimal mode — codec skips blank strings
        val systemInstruction = if (isMinimal) "" else buildSystemInstruction(state, planOnlySuffix)

        return ClipboardRequest(
            phase = if (state.allGatheredFiles.isEmpty() && freshFiles.isEmpty())
                ClipboardPhase.PLANNING else ClipboardPhase.CHAT,
            currentMessage = taskContent,
            projectName = state.projectContext.name,
            systemInstruction = systemInstruction,
            fileTree = if (isMinimal) "" else state.projectContext.fileTree.toCompactString(maxDepth = 4),
            freshFiles = freshFiles,
            previouslyGatheredPaths = previousPaths,
            chatHistory = if (isMinimal) emptyList() else state.dialogHistory.map { msg ->
                ClipboardHistoryEntry(
                    role = when (msg.role) {
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.SYSTEM -> "system"
                    },
                    content = msg.content
                )
            },
            attachedContext = if (isMinimal) null else state.attachedContext,
            ideErrors = state.ideErrors,
            planOnly = if (isMinimal) false else state.planOnly
        )
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Resolves the system instruction string for this turn.
     * Uses planning system prompt for the very first message (no gathered files yet),
     * and chat system prompt with optional plan-only suffix thereafter.
     */
    private fun buildSystemInstruction(state: ClipboardSessionState, planOnlySuffix: String): String {
        return if (state.allGatheredFiles.isEmpty()) {
            state.prompts.planningSystem
        } else {
            buildString {
                append(state.prompts.chatSystem)
                if (state.planOnly && planOnlySuffix.isNotBlank()) append(planOnlySuffix)
            }
        }
    }
}
```

### 2.Создать тест `ClipboardRequestBuilderTest.kt`

**Путь * *: `maxvibes-application/src/test/kotlin/com/maxvibes/application/service/ClipboardRequestBuilderTest.kt`

```kotlin
package com.maxvibes.application.service

import com . maxvibes . application . port . output . ChatMessageDTO
        import com . maxvibes . application . port . output . ChatRole
        import com . maxvibes . application . port . output . PromptTemplates
        import com . maxvibes . domain . model . context . ProjectContext
        import com . maxvibes . domain . model . context . FileTree
        import com . maxvibes . domain . model . interaction . ClipboardPhase
        import org . junit . jupiter . api . Test
        import org . junit . jupiter . api . Assertions . *

class ClipboardRequestBuilderTest {

    // ── Fixture helpers ───────────────────────────────────────────────

    private fun makeState(
        currentMessage: String = "fix the bug",
        gatheredFiles: Map<String, String> = emptyMap(),
        history: List<ChatMessageDTO> = emptyList(),
        planOnly: Boolean = false,
        attachedContext: String? = null,
        ideErrors: String? = null
    ) = ClipboardSessionState(
        currentMessage = currentMessage,
        projectContext = ProjectContext(
            name = "TestProject",
            fileTree = FileTree(emptyList()),
            language = "Kotlin"
        ),
        dialogHistory = history.toMutableList(),
        prompts = PromptTemplates(
            planningSystem = "PLANNING_PROMPT",
            chatSystem = "CHAT_PROMPT"
        ),
        allGatheredFiles = gatheredFiles.toMutableMap(),
        attachedContext = attachedContext,
        ideErrors = ideErrors,
        planOnly = planOnly
    )

    // ── Phase resolution ──────────────────────────────────────────────

    @Test
    fun `phase is PLANNING when no gathered files and no fresh files`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = emptyMap(),
            isFirstMessage = true
        )
        assertEquals(ClipboardPhase.PLANNING, req.phase)
    }

    @Test
    fun `phase is CHAT when fresh files are present`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = mapOf("src/Foo.kt" to "class Foo"),
            isFirstMessage = true
        )
        assertEquals(ClipboardPhase.CHAT, req.phase)
    }

    @Test
    fun `phase is CHAT when session already gathered files`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(gatheredFiles = mapOf("src/Bar.kt" to "class Bar")),
            freshFiles = emptyMap(),
            isFirstMessage = false
        )
        assertEquals(ClipboardPhase.CHAT, req.phase)
    }

    // ── Minimal mode ──────────────────────────────────────────────────

    @Test
    fun `minimal mode omits systemInstruction fileTree chatHistory`() {
        val history = mutableListOf(
            ChatMessageDTO(ChatRole.USER, "first message"),
            ChatMessageDTO(ChatRole.ASSISTANT, "response")
        )
        val req = ClipboardRequestBuilder.build(
            state = makeState(history = history),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false
        )
        assertTrue(req.systemInstruction.isBlank())
        assertTrue(req.fileTree.isBlank())
        assertTrue(req.chatHistory.isEmpty())
    }

    @Test
    fun `minimal mode uses last user message as task content`() {
        val history = listOf(
            ChatMessageDTO(ChatRole.USER, "first"),
            ChatMessageDTO(ChatRole.ASSISTANT, "reply"),
            ChatMessageDTO(ChatRole.USER, "second")
        )
        val req = ClipboardRequestBuilder.build(
            state = makeState(currentMessage = "original", history = history),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false
        )
        assertEquals("second", req.currentMessage)
    }

    @Test
    fun `minimal mode omits attachedContext`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(attachedContext = "some trace"),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false
        )
        assertNull(req.attachedContext)
    }

    @Test
    fun `minimal mode sets planOnly to false`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(planOnly = true),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false
        )
        assertFalse(req.planOnly)
    }

    // ── Full context mode ─────────────────────────────────────────────

    @Test
    fun `full mode includes chatHistory`() {
        val history = listOf(
            ChatMessageDTO(ChatRole.USER, "hi"),
            ChatMessageDTO(ChatRole.ASSISTANT, "hello")
        )
        val req = ClipboardRequestBuilder.build(
            state = makeState(history = history),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = true
        )
        assertEquals(2, req.chatHistory.size)
        assertEquals("user", req.chatHistory[0].role)
        assertEquals("assistant", req.chatHistory[1].role)
    }

    @Test
    fun `full mode includes previouslyGatheredPaths`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(gatheredFiles = mapOf("a.kt" to "A", "b.kt" to "B")),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = true
        )
        assertEquals(2, req.previouslyGatheredPaths.size)
        assertTrue(req.previouslyGatheredPaths.contains("a.kt"))
    }

    @Test
    fun `first message uses planningSystem prompt when no gathered files`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = emptyMap(),
            isFirstMessage = true
        )
        assertEquals("PLANNING_PROMPT", req.systemInstruction)
    }

    @Test
    fun `chat system prompt used after files gathered`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(gatheredFiles = mapOf("x.kt" to "")),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = true
        )
        assertTrue(req.systemInstruction.startsWith("CHAT_PROMPT"))
    }

    @Test
    fun `planOnly appends suffix to chat system prompt`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(planOnly = true, gatheredFiles = mapOf("x.kt" to "")),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = true,
            planOnlySuffix = "\n\n## PLAN ONLY"
        )
        assertTrue(req.systemInstruction.endsWith("## PLAN ONLY"))
    }

    // ── IDE errors ────────────────────────────────────────────────────

    @Test
    fun `ideErrors is present in full mode`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(ideErrors = "error: unresolved reference"),
            freshFiles = emptyMap(),
            isFirstMessage = true
        )
        assertEquals("error: unresolved reference", req.ideErrors)
    }

    @Test
    fun `ideErrors is present in minimal mode`() {
        // IDE errors are always included — they are per-turn, not context
        val req = ClipboardRequestBuilder.build(
            state = makeState(ideErrors = "error: type mismatch"),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false
        )
        assertEquals("error: type mismatch", req.ideErrors)
    }
}
```

## Проверка

```bash
    ./ gradlew : maxvibes -application:test
```

Ожидаемый результат : все тесты зелёные .

## Коммит

```
feat: add ClipboardRequestBuilder with unit tests
```
