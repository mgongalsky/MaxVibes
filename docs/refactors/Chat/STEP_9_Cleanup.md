# Шаг 9: Финальная чистка

## Контекст

Все UI -компоненты переключены на `ChatTreeService` . Теперь удаляем лишний код из `ChatHistoryService` и других мест . После этого шага `ChatHistoryService` — чистый persistence -адаптер.

**Предварительные условия : * * Шаги 1–8 полностью выполнены.Плагин работает корректно.

## Что нужно сделать

### 1.Почистить `ChatHistoryService`

        Из `ChatHistoryService` удалить все методы которые теперь живут в `ChatTreeService` :

**Удалить:**
-`getSessions()` — только если не используется напрямую
-`getActiveSession()` — делегирует в tree service
        -`getRootSessions()`
-`getChildren(sessionId)`
-`getParent(sessionId)`
-`getSessionPath(sessionId)`
-`getChildCount(sessionId)`
-`getDescendantCount(sessionId)`
-`isRoot(sessionId)`
-`buildTree()`
-`createNewSession()`
-`createBranch(parentSessionId, branchTitle)`
-`deleteSession(sessionId)` — публичный(не deleteSession из ChatSessionRepository)
-`deleteSessionCascade(sessionId)`
-`clearActiveSession()`
-`renameSession(sessionId, newTitle)`
-`addGlobalContextFile(relativePath)`
-`removeGlobalContextFile(relativePath)`
-Приватные helpers : `collectDescendantIds`, `recalculateChildDepths`, `recalculateDepths`, `trimOldSessions`

**Оставить(это реализация ChatSessionRepository):**
-`loadState` / `getState`(IntelliJ persistence)
-`getAllSessions()`
-`getSessionById(id)`
-`getActiveSessionId()`
-`setActiveSessionId(sessionId)`
-`saveSession(session)`
-`deleteSession(sessionId)` — из интерфейса ChatSessionRepository
-`getGlobalContextFiles()`
-`setGlobalContextFiles(files)`
-`getInstance(project)` companion

**Перед удалением : * * Убедись через Ctrl +Shift + F что каждый удаляемый метод нигде не вызывается .

### 2.Удалить `TokenUsageAccumulator` если не используется

**Файл:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/TokenUsageAccumulator.kt`

Проверь: поищи `TokenUsageAccumulator` по проекту . Если нигде не используется — удалить файл и регистрацию в `plugin.xml` если есть .

### 3.Убрать старые импорты

Пройдись по всем файлам где были изменения — удали неиспользуемые импорты.IntelliJ подсветит их серым .

### 4.Проверить `plugin.xml`

        Убедиться что `ChatHistoryService` всё ещё зарегистрирован как `projectService` (он нужен для persistence).

```xml
<projectService serviceImplementation ="com.maxvibes.plugin.chat.ChatHistoryService" / >
```

### 5.Обновить документацию

        Обновить `docs/ARCHITETURE.md` — добавить `ChatTreeService` и `ChatSessionRepository` в описание архитектуры .

Обновить `docs/CURRENT_STATUS.md` — отметить что chat domain refactoring завершён.

## Проверка после реализации

### Компиляция
```
./gradlew build
```
Полная сборка без warnings о неиспользуемом коде.

### Финальное ручное тестирование

1.Запустить плагин с нуля (без кэша)
2.Полная регрессия из шага 8
3.Загрузить * * старую * * историю(скопировать `maxvibes-chat-history.xml` из backup) — всё загружается
        4.Создать > 100 сессий — trimOldSessions работает

### Проверка размера `ChatHistoryService`

После чистки файл должен быть ~150 - 200 строк (было ~400). Если больше — проверь не осталось ли лишнего .

## Итоговая структура после всех 9 шагов

```
maxvibes - domain / model / chat /
        MessageRole.kt          ← шаг 1
TokenUsage.kt           ← шаг 2
ChatMessage.kt          ← шаг 3
ChatSession.kt          ← шаг 4
SessionTreeNode.kt      ← шаг 5

maxvibes - application /
        port / output /
        ChatSessionRepository.kt   ← шаг 6
service /
        ChatTreeService.kt         ← шаг 7
test /
        ChatSessionRepositoryContractTest.kt  ← шаг 6
ChatTreeServiceTest.kt               ← шаг 7

maxvibes - plugin / chat /
        ChatHistoryService.kt   ← только persistence (~150 строк)
```

## Коммит

```
refactor: ChatHistoryService is now pure persistence adapter, remove legacy tree logic
```

---

## Поздравляем!🎉

Рефакторинг завершён . Chat domain layer теперь:
-* * Тестируемый * * — `ChatTreeService` тестируется без IntelliJ
        -* * Чистый * * — доменные объекты без инфраструктурных аннотаций
-* * Понятный * * — каждый слой отвечает за своё
