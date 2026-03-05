# MaxVibes: Текущий статус

**Дата:** март 2026
**Версия:** 1.0.12-SNAPSHOT

## 📊 Общий статус

```
Chat Domain Refactoring: ██████████ 100% ✅
MVP Overall:            ██████████ 98%
```

| Компонент | Статус | Комментарий |
|-----------|--------|-------------|
| Domain Layer | ✅ 100% | Все модели готовы, включая chat domain |
| Shared Utils | ✅ 100% | Result type готов |
| Application Layer | ✅ 100% | Use Cases, порты, ChatTreeService |
| PSI Adapter | ✅ 100% | Все операции работают |
| LLM Adapter | ✅ 100% | LangChain4j интеграция готова |
| Plugin UI | ✅ 100% | Переключён на ChatTreeService |
| Chat Domain Refactoring | ✅ 100% | Шаги 1–9 завершены |
| Тесты | ✅ 75% | Unit тесты есть, включая ChatTreeService |
| Документация | ✅ 90% | Обновлена под новую архитектуру |

---

## ✅ Chat Domain Refactoring (Шаги 1–9) — ЗАВЕРШЁН

### Шаг 1: MessageRole → domain
- [x] `MessageRole.kt` перенесён в `maxvibes-domain/model/chat/`

### Шаг 2: TokenUsage → domain
- [x] `TokenUsage.kt` создан в `maxvibes-domain/model/chat/`

### Шаг 3: ChatMessage → domain
- [x] `ChatMessage.kt` создан в `maxvibes-domain/model/chat/`

### Шаг 4: ChatSession → domain
- [x] `ChatSession.kt` создан в `maxvibes-domain/model/chat/`
- [x] Иммутабельные методы: `withMessage()`, `cleared()`, `withTokens()`

### Шаг 5: SessionTreeNode → domain
- [x] `SessionTreeNode.kt` создан в `maxvibes-domain/model/chat/`

### Шаг 6: ChatSessionRepository → application port
- [x] `ChatSessionRepository.kt` — интерфейс в `maxvibes-application/port/output/`
- [x] `ChatSessionRepositoryContractTest.kt` — контрактные тесты
- [x] `ChatHistoryService` реализует интерфейс

### Шаг 7: ChatTreeService → application service
- [x] `ChatTreeService.kt` — вся бизнес-логика дерева в `maxvibes-application/service/`
- [x] `ChatTreeServiceTest.kt` — unit тесты без IntelliJ
- [x] Тестируется с fake-реализацией репозитория

### Шаг 8: UI переключён на ChatTreeService
- [x] `ChatPanel` использует `chatTreeService` вместо `ChatHistoryService` напрямую
- [x] `SessionTreePanel` принимает `ChatTreeService`
- [x] `ChatMessageController` работает через `chatTreeService`

### Шаг 9: Финальная чистка
- [x] `ChatHistoryService` — pure persistence adapter (~130 строк)
- [x] Удалены: все tree/create/delete методы, приватные helpers (collectDescendantIds, trimOldSessions)
- [x] `TokenUsageAccumulator` — удалён (не использовался)
- [x] `plugin.xml` — добавлена регистрация `ChatHistoryService` как `projectService`
- [x] Документация обновлена

---

## ✅ Итоговая структура Chat Domain

```
maxvibes-domain/model/chat/
MessageRole.kt          ← шаг 1
TokenUsage.kt           ← шаг 2
ChatMessage.kt          ← шаг 3
ChatSession.kt          ← шаг 4
SessionTreeNode.kt      ← шаг 5

maxvibes-application/
port/output/
ChatSessionRepository.kt   ← шаг 6 (interface)
service/
ChatTreeService.kt         ← шаг 7 (business logic)
test/
ChatSessionRepositoryContractTest.kt
ChatTreeServiceTest.kt

maxvibes-plugin/chat/
ChatHistoryService.kt   ← только persistence (~130 строк)
```

**Результат рефакторинга:**
- ✅ **Тестируемый** — ChatTreeService тестируется без IntelliJ
- ✅ **Чистый** — доменные объекты без инфраструктурных аннотаций
- ✅ **Понятный** — каждый слой отвечает за своё

---

## ✅ Готово (Legacy)

### Domain Layer (`maxvibes-domain`)
- [x] `ElementPath`, `CodeElement`, `Modification`, `ModificationResult`
- [x] Весь chat domain (MessageRole, TokenUsage, ChatMessage, ChatSession, SessionTreeNode)

### Application Layer (`maxvibes-application`)
- [x] `ModifyCodeUseCase`, `AnalyzeCodeUseCase`, `ContextAwareModifyUseCase`
- [x] `ChatSessionRepository` (port), `ChatTreeService`
- [x] Все порты: CodeRepository, LLMService, NotificationPort, ClipboardPort, etc.

### PSI Adapter
- [x] `PsiCodeRepository`, `PsiToDomainMapper`, `PsiNavigator`, `PsiModifier`, `KotlinElementFactory`

### LLM Adapter
- [x] OpenAI, Anthropic, Ollama через LangChain4j

### Plugin
- [x] `ChatHistoryService` — pure persistence
- [x] `MaxVibesService` — DI / Service Locator
- [x] `ChatPanel`, `SessionTreePanel`, `ChatMessageController` — все на ChatTreeService
- [x] Settings, Actions, Tool Window

---

## 🚧 TODO (Post-MVP)

- [ ] Streaming LLM responses
- [ ] Preview Dialog (diff перед применением)
- [ ] Integration тесты для LLM Adapter
- [ ] E2E тесты плагина
- [ ] trimOldSessions перенести в ChatTreeService (сейчас удалён из ChatHistoryService)

---

## 🐛 Известные проблемы

1. **Deprecation warnings в LangChain4j** — `ChatLanguageModel.generate()` deprecated, но работает
2. **PSI тесты** — требуют IntelliJ Test Framework, работают медленнее
3. **trimOldSessions** — удалён вместе с legacy кодом; если нужно ограничение 100 сессий — реализовать в ChatTreeService

---

## 🔧 Зависимости

| Dependency | Version |
|------------|---------|
| Kotlin | 1.9.21 |
| IntelliJ Platform | 2023.1.5 |
| LangChain4j | 1.0.0-beta3 |
| JUnit | 5.10.1 |
| MockK | 1.13.8 |
