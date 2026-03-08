# MaxVibes: Архитектура

## 🏗️ Обзор

MaxVibes построен по принципам **Clean Architecture** с чётким разделением на слои. Зависимости направлены только внутрь — от внешних слоёв к ядру (Domain).

```
┌─────────────────────────────────────────────────────────────────┐
│                     INFRASTRUCTURE                              │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │   Plugin    │ │  PSI API    │ │  LLM APIs   │               │
│  │  (IntelliJ) │ │  (IntelliJ) │ │(LangChain4j)│               │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
├─────────────────────────────────────────────────────────────────┤
│                    INTERFACE ADAPTERS                           │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │ PsiCode     │ │ LangChain   │ │ChatHistory  │               │
│  │ Repository  │ │ LLMService  │ │Service      │               │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
├─────────────────────────────────────────────────────────────────┤
│                      APPLICATION                                │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │ ModifyCode  │ │ AnalyzeCode │ │ChatTree     │  ← Use Cases  │
│  │ Service     │ │ Service     │ │Service      │               │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │ CodeRepo    │ │ LLMService  │ │ChatSession  │  ← Ports      │
│  │ (port)      │ │ (port)      │ │Repository   │               │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
├─────────────────────────────────────────────────────────────────┤
│                        DOMAIN                                   │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │ CodeElement │ │ Modification│ │ChatSession  │               │
│  │ ElementPath │ │ Result      │ │ChatMessage  │               │
│  └─────────────┘ └─────────────┘ │SessionTree  │               │
│                                  │Node         │               │
│                                  └─────────────┘               │
└─────────────────────────────────────────────────────────────────┘
```

## 📦 Модули

### maxvibes-domain (Core)

**Зависимости:** Никаких (только Kotlin stdlib)

Содержит чистые доменные модели без привязки к фреймворкам.

```
maxvibes-domain/
└── src/main/kotlin/com/maxvibes/domain/
└── model/
├── chat/
│   ├── MessageRole.kt       # USER / ASSISTANT / SYSTEM
│   ├── TokenUsage.kt        # Счётчики токенов (planning + chat)
│   ├── ChatMessage.kt       # Иммутабельное сообщение
│   ├── ChatSession.kt       # Сессия с деревянной структурой
│   └── SessionTreeNode.kt   # Узел дерева сессий
├── code/
│   ├── ElementPath.kt
│   └── CodeElement.kt
└── modification/
└── Modification.kt
```

---

### maxvibes-shared

**Зависимости:** kotlinx.coroutines

```
maxvibes-shared/
└── src/main/kotlin/com/maxvibes/shared/
└── result/
└── Result.kt    # Either-like тип: Success<T> | Failure<E>
```

---

### maxvibes-application

**Зависимости:** domain, shared

Use Cases и порты (интерфейсы для адаптеров).

```
maxvibes-application/
└── src/main/kotlin/com/maxvibes/application/
├── port/
│   ├── input/
│   │   ├── ModifyCodeUseCase.kt
│   │   ├── AnalyzeCodeUseCase.kt
│   │   └── ContextAwareModifyUseCase.kt
│   └── output/
│       ├── CodeRepository.kt
│       ├── LLMService.kt
│       ├── NotificationPort.kt
│       └── ChatSessionRepository.kt   # ← Порт для хранения сессий
└── service/
├── ModifyCodeService.kt
├── AnalyzeCodeService.kt
├── ContextAwareModifyService.kt
├── ClipboardInteractionService.kt
└── ChatTreeService.kt             # ← Бизнес-логика дерева сессий
```

#### ChatSessionRepository

Порт для persistence. Реализуется `ChatHistoryService` в plugin-модуле.

```kotlin
interface ChatSessionRepository {
fun getAllSessions(): List<ChatSession>
fun getSessionById(id: String): ChatSession?
fun getActiveSessionId(): String?
fun setActiveSessionId(sessionId: String)
fun saveSession(session: ChatSession)
fun deleteSession(sessionId: String)
fun getGlobalContextFiles(): List<String>
fun setGlobalContextFiles(files: List<String>)
}
```

#### ChatTreeService

Сервис application layer — вся бизнес-логика дерева сессий. **Не зависит от IntelliJ**, тестируется без IDE.

---

### maxvibes-adapter-psi

**Зависимости:** domain, application, shared, IntelliJ Platform, Kotlin Plugin

```
maxvibes-adapter-psi/
└── src/main/kotlin/com/maxvibes/adapter/psi/
├── PsiCodeRepository.kt
├── context/
│   ├── IntellijIdeErrorsAdapter.kt
│   └── PsiProjectContextProvider.kt
├── mapper/
│   └── PsiToDomainMapper.kt
├── operation/
│   ├── PsiNavigator.kt
│   └── PsiModifier.kt
└── kotlin/
└── KotlinElementFactory.kt
```

---

### maxvibes-adapter-llm

**Зависимости:** domain, application, shared, LangChain4j

```
maxvibes-adapter-llm/
└── src/main/kotlin/com/maxvibes/adapter/llm/
├── dto/
│   ├── LLMResponseDTOs.kt
│   └── ChatMessageMapper.kt    # ← Единственное место ChatMessage → ChatMessageDTO
├── LangChainLLMService.kt
└── LLMServiceFactory.kt
```

| Провайдер | Модели |
|-----------|--------|
| OpenAI | gpt-4o, gpt-4o-mini |
| Anthropic | claude-sonnet-4, claude-opus-4 |
| Ollama | llama3.2, codellama, mistral |

---

### maxvibes-plugin

**Зависимости:** Все модули

```
maxvibes-plugin/
└── src/main/kotlin/com/maxvibes/plugin/
├── action/
├── chat/
│   └── ChatHistoryService.kt    # ← Pure persistence adapter (~130 LOC)
├── clipboard/
├── service/
│   ├── MaxVibesService.kt       # Service Locator / DI
│   └── IdeNotificationService.kt
├── settings/
└── ui/
├── ChatPanel.kt             # View: UI + event routing (~515 LOC) ✅
├── ChatMessageController.kt # Presenter: messages + attachments + sessions
├── ConversationRenderer.kt  # Message filtering and display formatting
├── InteractionModeManager.kt # Mode state machine (API/Clipboard/CheapAPI)
├── ChatPanelState.kt        # State snapshot for render()
├── SessionTreePanel.kt
└── ConversationPanel.kt
```

#### ChatPanel — статус: Хорошо ✅

Thin View — отображает состояние и маршрутизирует события. Соответствует SRP.

- `render(ChatPanelState)` — единая точка обновления UI
- Не содержит бизнес-логики (attachment/session operations делегированы Controller)
- Использует `ConversationRenderer` для отображения истории
- Использует `InteractionModeManager` для state machine режимов

#### ChatHistoryService

Pure persistence adapter. Реализует `ChatSessionRepository`. Управляет только XML-сериализацией через IntelliJ `PersistentStateComponent`. Никакой бизнес-логики.

---

## 🔄 Chat Domain Flow

```
UI (ChatPanel / SessionTreePanel)
│ вызывает
▼
┌─────────────────────┐
│   ChatTreeService   │  ← Application layer, testable
│  (бизнес-логика)    │
└─────────────────────┘
│ вызывает
▼
┌─────────────────────────┐
│  ChatSessionRepository  │  ← Port (interface)
└─────────────────────────┘
│ реализует
▼
┌─────────────────────────┐
│  ChatHistoryService     │  ← Adapter (IntelliJ plugin layer)
│  (XML persistence)      │
└─────────────────────────┘
│
▼
maxvibes-chat-history.xml
```

---

## 🧪 Тестирование

| Уровень | Модуль | Что тестируем |
|---------|--------|---------------|
| Unit | domain | ElementPath, ChatSession, ChatMessage |
| Unit | shared | Result |
| Unit | application | ChatTreeService (без IntelliJ!) |
| Contract | application | ChatSessionRepositoryContractTest |
| Unit | adapter-llm | ChatMessageMapperTest |
| Integration | adapter-psi | PsiToDomainMapper, PsiNavigator |

```bash
./gradlew :maxvibes-application:test   # ChatTreeService, ChatSessionRepository
./gradlew :maxvibes-domain:test
./gradlew :maxvibes-shared:test
./gradlew :maxvibes-adapter-llm:test   # ChatMessageMapperTest
```

---

## 📐 Принципы дизайна

1. **Dependency Rule** — зависимости только внутрь (domain ← application ← adapters ← plugin)
2. **Port/Adapter Pattern** — интерфейсы в application, реализации в адаптерах
3. **Testable Domain** — ChatTreeService тестируется без IntelliJ
4. **Immutable Domain Models** — `data class`, иммутабельные
5. **Result Type** — явная обработка ошибок вместо исключений
6. **Single Responsibility** — ChatPanel отображает, ChatMessageController управляет
7. **Single Source of Truth** — ChatMessageMapper единственное место маппинга ChatMessage → DTO
