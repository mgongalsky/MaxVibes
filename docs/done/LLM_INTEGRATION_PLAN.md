# MaxVibes: План интеграции LLM через Koog

## 🎯 Цель

Заменить `MockLLMService` на реальную интеграцию с LLM через Koog фреймворк от JetBrains.

## 📚 Что такое Koog?

**Koog** — это Kotlin-first фреймворк для создания AI агентов от JetBrains.

**Ключевые возможности:**
- Graph-based и простые агенты
- Встроенная поддержка tools (function calling)
- Structured output (JSON schema)
- Multi-provider: OpenAI, Anthropic, Ollama
- MCP (Model Context Protocol) интеграция
- Persistence и memory

**GitHub:** https://github.com/JetBrains/koog  
**Docs:** https://koog.ai/

---

## 🏗️ Архитектура интеграции

```
┌─────────────────────────────────────────────────────────────────┐
│                     maxvibes-adapter-llm                        │
│                                                                 │
│  ┌─────────────────┐     ┌─────────────────┐                   │
│  │ KoogLLMService  │────▶│   CoderAgent    │                   │
│  │ (LLMService)    │     │   (Koog Agent)  │                   │
│  └─────────────────┘     └─────────────────┘                   │
│           │                      │                             │
│           │              ┌───────┴───────┐                     │
│           │              │               │                     │
│           ▼              ▼               ▼                     │
│  ┌─────────────────┐ ┌────────┐ ┌────────────────┐            │
│  │ PromptBuilder   │ │ Tools  │ │ StructuredOutput│           │
│  └─────────────────┘ └────────┘ └────────────────┘            │
│           │                                                    │
│           ▼                                                    │
│  ┌─────────────────┐                                          │
│  │  LLMProvider    │◀─── OpenAI / Anthropic / Ollama          │
│  └─────────────────┘                                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📋 План реализации

### Phase 1: Базовая инфраструктура

**Время:** ~2-3 часа

#### 1.1 Создать LLMProvider abstraction

```kotlin
// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/provider/LLMProvider.kt

interface LLMProvider {
    val name: String
    val modelId: String
    
    suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<Tool>? = null
    ): CompletionResult
}

data class ChatMessage(
    val role: Role,
    val content: String
)

enum class Role { SYSTEM, USER, ASSISTANT }

sealed class CompletionResult {
    data class Text(val content: String) : CompletionResult()
    data class ToolCall(val name: String, val arguments: String) : CompletionResult()
    data class Error(val message: String) : CompletionResult()
}
```

#### 1.2 Реализовать OpenAI Provider

```kotlin
// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/provider/OpenAIProvider.kt

class OpenAIProvider(
    private val apiKey: String,
    override val modelId: String = "gpt-4o"
) : LLMProvider {
    
    override val name = "OpenAI"
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
    
    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<Tool>?
    ): CompletionResult {
        // Использовать Koog OpenAI integration
        val executor = simpleOpenAIExecutor(apiKey)
        // ...
    }
}
```

#### 1.3 Реализовать Anthropic Provider

```kotlin
// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/provider/AnthropicProvider.kt

class AnthropicProvider(
    private val apiKey: String,
    override val modelId: String = "claude-sonnet-4-20250514"
) : LLMProvider {
    
    override val name = "Anthropic"
    
    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<Tool>?
    ): CompletionResult {
        // Использовать Koog Anthropic integration
        val executor = simpleAnthropicExecutor(apiKey)
        // ...
    }
}
```

---

### Phase 2: CoderAgent

**Время:** ~3-4 часа

#### 2.1 Определить Tools для агента

```kotlin
// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/agent/tools/CodeTools.kt

@Serializable
data class CreateElementArgs(
    val targetPath: String,
    val elementKind: String,  // CLASS, FUNCTION, PROPERTY
    val content: String,
    val position: String = "LAST_CHILD"
)

@Serializable
data class ReplaceElementArgs(
    val targetPath: String,
    val newContent: String
)

@Serializable
data class DeleteElementArgs(
    val targetPath: String
)

// Tool definitions для Koog
val createElementTool = tool<CreateElementArgs>(
    name = "create_element",
    description = "Create a new code element (class, function, property) in the specified location"
) { args ->
    // Возвращает JSON для ModifyCodeService
    Modification.CreateElement(
        targetPath = ElementPath(args.targetPath),
        elementKind = ElementKind.valueOf(args.elementKind),
        content = args.content,
        position = InsertPosition.valueOf(args.position)
    )
}

val replaceElementTool = tool<ReplaceElementArgs>(
    name = "replace_element",
    description = "Replace an existing code element with new content"
) { args ->
    Modification.ReplaceElement(
        targetPath = ElementPath(args.targetPath),
        newContent = args.newContent
    )
}

val deleteElementTool = tool<DeleteElementArgs>(
    name = "delete_element", 
    description = "Delete a code element"
) { args ->
    Modification.DeleteElement(
        targetPath = ElementPath(args.targetPath)
    )
}
```

#### 2.2 Создать CoderAgent

```kotlin
// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/agent/CoderAgent.kt

class CoderAgent(
    private val provider: LLMProvider
) {
    private val systemPrompt = """
        You are an expert Kotlin developer assistant. Your task is to modify code based on user instructions.
        
        ## Available Tools
        
        - create_element: Add new class/function/property to existing code
        - replace_element: Replace existing code element
        - delete_element: Remove code element
        
        ## Path Format
        
        Use ElementPath format: file:path/to/File.kt/class[ClassName]/function[funcName]
        
        ## Rules
        
        1. Generate valid Kotlin code
        2. Preserve existing code style
        3. Use minimal, focused changes
        4. Always specify complete element content
        
        ## Example
        
        User: "add toString method to User class"
        You should call: create_element(
            targetPath = "file:src/User.kt/class[User]",
            elementKind = "FUNCTION",
            content = "override fun toString(): String = \"User(name=\$name)\""
        )
    """.trimIndent()
    
    suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): List<Modification> {
        // 1. Build prompt with context
        val prompt = buildPrompt(instruction, context)
        
        // 2. Call LLM with tools
        val result = provider.complete(
            messages = listOf(
                ChatMessage(Role.SYSTEM, systemPrompt),
                ChatMessage(Role.USER, prompt)
            ),
            tools = listOf(createElementTool, replaceElementTool, deleteElementTool)
        )
        
        // 3. Parse tool calls into Modifications
        return parseToolCalls(result)
    }
    
    private fun buildPrompt(instruction: String, context: LLMContext): String {
        return buildString {
            appendLine("## Current Code")
            appendLine()
            context.relevantCode.forEach { element ->
                appendLine("### ${element.path}")
                appendLine("```kotlin")
                appendLine(element.toCompactString())
                appendLine("```")
                appendLine()
            }
            appendLine("## Instruction")
            appendLine(instruction)
        }
    }
}
```

---

### Phase 3: KoogLLMService

**Время:** ~2 часа

#### 3.1 Реализовать KoogLLMService

```kotlin
// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/KoogLLMService.kt

class KoogLLMService(
    private val providerFactory: LLMProviderFactory
) : LLMService {
    
    override suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): Result<List<Modification>, LLMError> {
        return try {
            val provider = providerFactory.getProvider()
            val agent = CoderAgent(provider)
            
            val modifications = agent.generateModifications(instruction, context)
            
            Result.Success(modifications)
        } catch (e: Exception) {
            Result.Failure(LLMError.NetworkError(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): Result<AnalysisResponse, LLMError> {
        return try {
            val provider = providerFactory.getProvider()
            
            val prompt = buildAnalysisPrompt(question, codeElements)
            val result = provider.complete(
                messages = listOf(
                    ChatMessage(Role.SYSTEM, "You are a code analysis assistant."),
                    ChatMessage(Role.USER, prompt)
                )
            )
            
            when (result) {
                is CompletionResult.Text -> Result.Success(
                    AnalysisResponse(answer = result.content)
                )
                is CompletionResult.Error -> Result.Failure(
                    LLMError.InvalidResponse(result.message)
                )
                else -> Result.Failure(LLMError.InvalidResponse("Unexpected response"))
            }
        } catch (e: Exception) {
            Result.Failure(LLMError.NetworkError(e.message ?: "Unknown error"))
        }
    }
}
```

---

### Phase 4: Настройки и конфигурация

**Время:** ~2 часа

#### 4.1 Settings UI

```kotlin
// maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettings.kt

@State(name = "MaxVibesSettings", storages = [Storage("maxvibes.xml")])
class MaxVibesSettings : PersistentStateComponent<MaxVibesSettings.State> {
    
    data class State(
        var provider: String = "openai",
        var openaiApiKey: String = "",
        var anthropicApiKey: String = "",
        var modelId: String = "gpt-4o"
    )
    
    private var state = State()
    
    override fun getState() = state
    override fun loadState(state: State) { this.state = state }
    
    companion object {
        fun getInstance(): MaxVibesSettings = 
            ApplicationManager.getApplication().getService(MaxVibesSettings::class.java)
    }
}
```

#### 4.2 Settings Configurable

```kotlin
// maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettingsConfigurable.kt

class MaxVibesSettingsConfigurable : Configurable {
    
    private var panel: JPanel? = null
    private var providerCombo: JComboBox<String>? = null
    private var openaiKeyField: JBPasswordField? = null
    private var anthropicKeyField: JBPasswordField? = null
    private var modelField: JBTextField? = null
    
    override fun createComponent(): JComponent {
        // Build settings form
    }
    
    override fun isModified(): Boolean {
        // Check if settings changed
    }
    
    override fun apply() {
        // Save settings
    }
}
```

---

### Phase 5: Интеграция и тестирование

**Время:** ~2-3 часа

#### 5.1 Обновить MaxVibesService

```kotlin
// maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/MaxVibesService.kt

@Service(Service.Level.PROJECT)
class MaxVibesService(private val project: Project) {

    val llmService: LLMService by lazy {
        val settings = MaxVibesSettings.getInstance().state
        
        if (settings.openaiApiKey.isNotEmpty() || settings.anthropicApiKey.isNotEmpty()) {
            // Real LLM
            val factory = LLMProviderFactory(settings)
            KoogLLMService(factory)
        } else {
            // Fallback to mock
            MockLLMService()
        }
    }
    
    // ... rest of the service
}
```

#### 5.2 Добавить тесты

```kotlin
// maxvibes-adapter-llm/src/test/kotlin/com/maxvibes/adapter/llm/CoderAgentTest.kt

class CoderAgentTest {
    
    @Test
    fun `should generate CreateElement for add function instruction`() {
        // Mock provider
        val provider = mockk<LLMProvider>()
        coEvery { provider.complete(any(), any()) } returns CompletionResult.ToolCall(
            name = "create_element",
            arguments = """{"targetPath":"file:Test.kt/class[Test]","elementKind":"FUNCTION","content":"fun test() {}"}"""
        )
        
        val agent = CoderAgent(provider)
        val result = runBlocking {
            agent.generateModifications("add function test", mockContext())
        }
        
        assertEquals(1, result.size)
        assertTrue(result[0] is Modification.CreateElement)
    }
}

```

---

## 📁 Структура файлов после реализации

```
maxvibes-adapter-llm/
└── src/
    ├── main/kotlin/com/maxvibes/adapter/llm/
    │   ├── KoogLLMService.kt              # Главный сервис
    │   ├── agent/
    │   │   ├── CoderAgent.kt              # Агент для генерации кода
    │   │   └── tools/
    │   │       └── CodeTools.kt           # Tool definitions
    │   ├── prompt/
    │   │   └── PromptBuilder.kt           # Построение промптов
    │   └── provider/
    │       ├── LLMProvider.kt             # Интерфейс провайдера
    │       ├── LLMProviderFactory.kt      # Фабрика провайдеров
    │       ├── OpenAIProvider.kt          # OpenAI реализация
    │       └── AnthropicProvider.kt       # Anthropic реализация
    └── test/kotlin/com/maxvibes/adapter/llm/
        ├── KoogLLMServiceTest.kt
        └── agent/
            └── CoderAgentTest.kt
```

---

## ⏱️ Оценка времени

| Phase | Время | Описание |
|-------|-------|----------|
| Phase 1 | 2-3 часа | LLMProvider, OpenAI, Anthropic |
| Phase 2 | 3-4 часа | CoderAgent, Tools |
| Phase 3 | 2 часа | KoogLLMService |
| Phase 4 | 2 часа | Settings UI |
| Phase 5 | 2-3 часа | Интеграция, тесты |
| **Total** | **11-14 часов** | |

---

## 🔑 Необходимые API ключи

Для тестирования понадобится хотя бы один:

1. **OpenAI API Key** — https://platform.openai.com/api-keys
2. **Anthropic API Key** — https://console.anthropic.com/

---

## 📚 Ресурсы

- [Koog Documentation](https://koog.ai/)
- [Koog GitHub](https://github.com/JetBrains/koog)
- [Koog Examples](https://github.com/JetBrains/koog/tree/main/examples)
- [OpenAI API Reference](https://platform.openai.com/docs/api-reference)
- [Anthropic API Reference](https://docs.anthropic.com/claude/reference)

---

## ✅ Checklist

- [ ] Phase 1: LLMProvider abstraction
    - [ ] LLMProvider interface
    - [ ] OpenAIProvider
    - [ ] AnthropicProvider
    - [ ] LLMProviderFactory

- [ ] Phase 2: CoderAgent
    - [ ] CodeTools (create, replace, delete)
    - [ ] CoderAgent with system prompt
    - [ ] Tool call parsing

- [ ] Phase 3: KoogLLMService
    - [ ] Implement generateModifications
    - [ ] Implement analyzeCode
    - [ ] Error handling

- [ ] Phase 4: Settings
    - [ ] MaxVibesSettings (persistent state)
    - [ ] Settings UI panel
    - [ ] API key management

- [ ] Phase 5: Integration
    - [ ] Update MaxVibesService
    - [ ] Unit tests
    - [ ] Integration tests
    - [ ] Manual testing