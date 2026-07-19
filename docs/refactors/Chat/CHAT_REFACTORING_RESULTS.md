# CHAT_REFACTORING_PLAN — Результаты выполнения

> Дата проверки: 2026-03-05
> Ветка: `refactor/chat-domain-layer`

---

## ШАГ 1 — MessageRole в domain ✅

- `MessageRole.kt` создан в `maxvibes-domain/.../model/chat/`
- Чистый enum: `USER, ASSISTANT, SYSTEM` без аннотаций и зависимостей
- `ChatHistoryService.kt` импортирует из domain, старый enum удалён
- **Тесты:** не требовались по плану (чистый enum без логики)

---

## ШАГ 2 — TokenUsage в domain ✅

**Код:**
- `TokenUsage.kt` создан в `maxvibes-domain/.../model/chat/`
- Поля: `planningInput`, `planningOutput`, `chatInput`, `chatOutput`
- Вычисляемые свойства: `totalInput`, `totalOutput`, `total`
- Методы: `addPlanning()`, `addChat()`, `isEmpty()`, `formatDisplay()`
- `companion object { val EMPTY = TokenUsage() }`
- Улучшение vs план: `Locale.US` в `String.format`

**Тесты: `TokenUsageTest.kt` — 13 тестов ✅**
- Пустой экземпляр: нули, isEmpty() ✓
- addPlanning / addChat: накопление значений ✓
- Несколько сложений подряд ✓
- totalInput / totalOutput как суммы ✓
- formatDisplay: пустая строка для нуля, секции Plan/Chat, стоимость ($) ✓
- Иммутабельность: addPlanning возвращает новый экземпляр ✓
- EMPTY == TokenUsage() ✓

---

## ШАГ 3 — ChatMessage в domain ✅

**Код:**
- `ChatMessage.kt` создан в `maxvibes-domain/.../model/chat/`
- Поля: `id` (UUID по умолчанию), `role: MessageRole`, `content: String`, `timestamp: Long`
- Иммутабельный `data class`
- Старый `ChatMessage` переименован в `XmlChatMessage` — XML-совместимость сохранена
- Методы конвертации: `toDomain()`, `fromDomain()`, `toChatMessageDTO()`

**Тесты: `ChatMessageTest.kt` — 6 тестов ✅**
- Создание с явными полями ✓
- UUID по умолчанию (длина 36, непустой) ✓
- timestamp по умолчанию в диапазоне before..after ✓
- Иммутабельность через copy() ✓
- Равенство двух экземпляров с одинаковыми полями ✓
- Поддержка всех трёх ролей (USER, ASSISTANT, SYSTEM) ✓

---

## ШАГ 4 — ChatSession в domain ✅

**Код:**
- `ChatSession.kt` создан в `maxvibes-domain/.../model/chat/`
- Поля: `id`, `title`, `parentId`, `depth`, `messages`, `tokenUsage`, `createdAt`, `updatedAt`
- Вычисляемое свойство `isRoot`
- Методы: `withMessage()`, `withTitle()`, `withDepth()`, `withParent()`, `addPlanningTokens()`, `addChatTokens()`, `touch()`, `cleared()`
- `XmlChatSession` с `toDomain()` / `fromDomain()` — XML-совместимость сохранена

**Тесты: `ChatSessionTest.kt` — 15 тестов ✅**
- Значения по умолчанию (title="New Chat", depth=0, isRoot=true) ✓
- withMessage: добавление сообщения ✓
- Авто-тайтл из первого USER-сообщения ✓
- Тайтл не меняется после второго сообщения ✓
- Обрезка длинного тайтла до 40 символов + "..." (итого 43) ✓
- ASSISTANT-сообщение не изменяет тайтл ✓
- withTitle: переименование и blank → "Untitled" ✓
- isRoot: true для null parentId, false для не-null ✓
- addPlanningTokens / addChatTokens: накопление в tokenUsage ✓
- cleared(): очистка списка сообщений ✓
- Иммутабельность: withMessage возвращает новый экземпляр ✓
- withParent: обновление parentId и depth ✓

---

## ШАГ 5 — SessionTreeNode в domain ✅

**Код:**
- `SessionTreeNode.kt` создан в `maxvibes-domain/.../model/chat/`
- Поля: `session: ChatSession`, `children: List<SessionTreeNode>` (иммутабельный)
- Делегирующие свойства: `id`, `title`, `depth`, `hasChildren`
- Метод `withChildren()`
- Старый `SessionTreeNode` из `ChatHistoryService.kt` удалён

**Тесты: `SessionTreeNodeTest.kt` — 7 тестов ✅**
- Листовой узел: hasChildren=false, children пустой ✓
- Узел с детьми: hasChildren=true ✓
- Делегирование id, title, depth ✓
- withChildren: иммутабельная замена детей ✓
- Трёхуровневое дерево (root → child → grandchild) ✓

---

## ШАГ 6 — Порт ChatSessionRepository в application ✅

**Код:**
- `ChatSessionRepository.kt` создан в `maxvibes-application/.../port/output/`
- 8 методов интерфейса: `getAllSessions()`, `getSessionById()`, `getActiveSessionId()`, `setActiveSessionId()`, `saveSession()`, `deleteSession()`, `getGlobalContextFiles()`, `setGlobalContextFiles()`
- Только доменные типы, никаких IntelliJ-зависимостей
- `ChatHistoryService implements ChatSessionRepository`, все методы через `override`

**Тесты: `InMemoryChatSessionRepository` создана ✅**
- Используется как fake в `ChatTreeServiceTest` — значит файл существует в `maxvibes-application/src/test/.../port/output/`
- Отдельного `ChatSessionRepositoryContractTest` не обнаружено, но контракт покрывается косвенно через `ChatTreeServiceTest`

---

## ШАГ 7 — ChatTreeService в application ✅

**Код:**
- `ChatTreeService.kt` создан в `maxvibes-application/.../service/`
- Принимает `ChatSessionRepository` в конструкторе — нет IntelliJ-зависимостей
- Query: `getActiveSession()`, `setActiveSession()`, `getAllSessions()`, `getSessionById()`, `getSessions()`, `getRootSessions()`, `getChildren()`, `getParent()`, `getSessionPath()`, `getChildCount()`, `getDescendantCount()`, `buildTree()`
- Mutation: `createNewSession()`, `createBranch()`, `renameSession()`, `addMessage()`, `addPlanningTokens()`, `addChatTokens()`, `clearSession()`, `deleteSession()`, `deleteSessionCascade()`
- Context files: `getGlobalContextFiles()`, `setGlobalContextFiles()`, `addGlobalContextFile()`, `removeGlobalContextFile()`
- Хелперы: `collectDescendantIds()`, `recalculateChildDepths()`, `trimOldSessions()`
- `MAX_SESSIONS = 100`
- Подключён в `MaxVibesService` как lazy: `chatSessionRepository` + `chatTreeService`

**Тесты: `ChatTreeServiceTest.kt` — 30+ тестов ✅**
- createNewSession: создаёт root, устанавливает active ✓
- getActiveSession: создаёт если нет, возвращает существующий ✓
- createBranch: parentId, depth, active, кастомный тайтл, null для несуществующего ✓
- Вложенные ветки: глубина 0→1→2 ✓
- getChildren: пустой для листа, только прямые дети ✓
- getSessionPath: root (1 элемент), child (2), grandchild (3) ✓
- deleteSession: удаление, re-parenting детей к grandparent, превращение в root ✓
- deleteSessionCascade: удаление узла и всех потомков ✓
- addMessage: добавление и персистенция ✓
- renameSession: обновление, null для несуществующего ✓
- buildTree: пустое, корневые элементы, вложенность ✓
- Token tracking: накопление planning и chat токенов ✓
- Context files: add, дедупликация, remove, нормализация `\` → `/` ✓

---

## ШАГ 8 — Переключение UI на ChatTreeService ✅

| Файл | Статус | Детали |
|------|--------|--------|
| `ChatPanel.kt` | ✅ | Полностью на `service.chatTreeService`, нет прямых вызовов `ChatHistoryService` |
| `SessionTreePanel.kt` | ✅ | Принимает `ChatTreeService` в конструкторе, `buildTree()`, `renameSession()` |
| `ChatMessageController.kt` | ✅ | `addMessage()`, `addPlanningTokens()`, `addChatTokens()` через `chatTreeService` |
| `ConversationPanel.kt` | ✅ | Не работает с историей сессий вообще |
| `ChatDialogsHelper.kt` | ✅ | Принимает `ChatTreeService` в параметре |
| `ChatNavigationHelper.kt` | ✅ | Только PSI-навигация, чат не касается |

---

## ШАГ 9 — Чистка ⚠️ Частично

### Выполнено ✅
- `ChatHistoryService` сократился до ~150 строк
- Удалены методы бизнес-логики: `buildTree()`, `createNewSession()`, `createBranch()`, `getChildren()`, `getParent()`, `getSessionPath()`, `getChildCount()`, `getSessions()`, `clearActiveSession()`, `renameSession()`, `addGlobalContextFile()`, `removeGlobalContextFile()`
- `recalculateChildDepths()` / `recalculateDepths()` сохранены — нужны для `loadState()` (корректно)

### Не выполнено ❌

**1. Критический баг — дублирование re-parenting в `deleteSession`**

`ChatTreeService.deleteSession()` обновляет детей через `withParent()` + `saveSession()`, затем вызывает `repository.deleteSession()`. Но `ChatHistoryService.deleteSession()` **снова** делает re-parenting тех же детей — двойной пересчёт при каждом удалении.

По плану `ChatHistoryService.deleteSession()` должна содержать только:
```kotlin
override fun deleteSession(sessionId: String) {
state.sessions.removeIf { it.id == sessionId }
if (state.activeSessionId == sessionId) {
state.activeSessionId = state.sessions.firstOrNull()?.id
}
}
```

**2. `TokenUsageAccumulator.kt` не удалён**
- Файл присутствует в `maxvibes-plugin/.../service/`
- В просмотренных файлах нигде не используется
- Нужно проверить `plugin.xml` и удалить

**3. Документация не обновлена**
- `ARCHITETURE.md` и `CURRENT_STATUS.md` не проверялись
- Должны отражать новую архитектуру: `ChatTreeService`, `ChatSessionRepository`

---

## Итоговая таблица

| Шаг | Код | Тесты | Статус |
|-----|-----|-------|--------|
| 1. MessageRole | ✅ | — | ✅ Полностью |
| 2. TokenUsage | ✅ | ✅ 13 тестов | ✅ Полностью |
| 3. ChatMessage | ✅ | ✅ 6 тестов | ✅ Полностью |
| 4. ChatSession | ✅ | ✅ 15 тестов | ✅ Полностью |
| 5. SessionTreeNode | ✅ | ✅ 7 тестов | ✅ Полностью |
| 6. ChatSessionRepository | ✅ | ✅ InMemoryRepo | ✅ Полностью |
| 7. ChatTreeService | ✅ | ✅ 30+ тестов | ✅ Полностью |
| 8. Switch UI | ✅ | — | ✅ Полностью |
| 9. Cleanup | ⚠️ | — | ⚠️ 3 пункта открыты |

### Открытые задачи
1. Исправить `ChatHistoryService.deleteSession()` — убрать дублирующий re-parenting
2. Удалить `TokenUsageAccumulator.kt` (проверить `plugin.xml`)
3. Обновить `ARCHITETURE.md` и `CURRENT_STATUS.md`
