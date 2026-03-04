# Шаг 8: Переключить UI на `ChatTreeService`

## Контекст

На этом шаге мы переключаем все UI - компоненты с прямого использования `ChatHistoryService` на `ChatTreeService`.UI работает с доменными объектами, а persistence скрыта за портом.Делаем по одному файлу за раз — каждый подшаг компилируется и тестируется .

**Предварительные условия : * * Шаги 1–7 выполнены . `ChatTreeService` подключён в `MaxVibesService`.

## Как получить `ChatTreeService` в UI

```kotlin
// В любом UI классе у которого есть project:
val chatTreeService = MaxVibesService.getInstance(project).chatTreeService
```

## Подшаг 8.1: `ChatMessageController`

**Файл:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatMessageController.kt`

Что изменить :
-`historyService.getActiveSession()` → `chatTreeService.getActiveSession()`
-`session.addMessage(...)` → `chatTreeService.addMessage(session.id, role, content)`
-`session.addPlanningTokens(...)` / `session.addChatTokens(...)` → `chatTreeService.addPlanningTokens(...)` / `chatTreeService.addChatTokens(...)`
-Прямые мутации `session.planningInputTokens += ...` → удалить, использовать методы сервиса

**Тест:** Отправить сообщение в API -режиме — сообщение появляется, токены считаются .

**Коммит:** `refactor: switch ChatMessageController to ChatTreeService`

---

## Подшаг 8.2: `SessionTreePanel`

**Файл:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/SessionTreePanel.kt`

Что изменить :
-`historyService.buildTree()` → `chatTreeService.buildTree()`
-`historyService.getActiveSession()` → `chatTreeService.getActiveSession()`
-`historyService.setActiveSession(id)` → `chatTreeService.setActiveSession(id)`
-`historyService.deleteSession(id)` → `chatTreeService.deleteSession(id)`
-`historyService.deleteSessionCascade(id)` → `chatTreeService.deleteSessionCascade(id)`
-`historyService.renameSession(id, title)` → `chatTreeService.renameSession(id, title)`
-`historyService.createBranch(id)` → `chatTreeService.createBranch(id)`

**Тест:** Дерево сессий отображается, expand / collapse, переименование, удаление, создание ветки — всё работает.

**Коммит:** `refactor: switch SessionTreePanel to ChatTreeService`

---

## Подшаг 8.3: `ConversationPanel`

**Файл:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ConversationPanel.kt`

Что изменить :
-Загрузка сообщений : `session.messages` теперь `List<domain.ChatMessage>` — убедиться что рендеринг использует `message.role`, `message.content`, `message.timestamp`
-`session.tokenUsage.formatDisplay()` вместо `session.formatTokenDisplay()`
-Удалить прямые обращения к `ChatHistoryService` если есть

**Тест:** Открыть сессию — все сообщения отображаются корректно с правильными ролями и временными метками.Token display работает.

**Коммит:** `refactor: switch ConversationPanel to domain ChatMessage`

---

## Подшаг 8.4: `ChatPanel`

**Файл:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt`

Это самый большой файл (~700 строк). Изменить:
-`ChatHistoryService.getInstance(project)` → `MaxVibesService.getInstance(project).chatTreeService`
-`historyService.getActiveSession()` → `chatTreeService.getActiveSession()`
-`historyService.createNewSession()` → `chatTreeService.createNewSession()`
-`historyService.setActiveSession(id)` → `chatTreeService.setActiveSession(id)`
-`historyService.getSessionPath(id)` → `chatTreeService.getSessionPath(id)`(для breadcrumb)
-`historyService.clearActiveSession()` → `chatTreeService.clearSession(activeId)`
-Context files : `historyService.getGlobalContextFiles()` → `chatTreeService.getGlobalContextFiles()`
        -Все прямые мутации `session.xxx = ...` заменить на методы сервиса

**Тест:** Полный smoke test — отправить сообщение, переключить сессию, посмотреть breadcrumb, очистить чат .

**Коммит:** `refactor: switch ChatPanel to ChatTreeService`

---

## Подшаг 8.5: `ChatDialogsHelper` и `ChatNavigationHelper`

**Файлы:**
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatDialogsHelper.kt`
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatNavigationHelper.kt`

Аналогично предыдущим — заменить обращения к `ChatHistoryService` на `chatTreeService`.

**Тест:** Диалог переименования работает.Навигация между сессиями работает . Context files диалог работает.

**Коммит:** `refactor: switch ChatDialogsHelper and ChatNavigationHelper to ChatTreeService`

---

## Финальная проверка после всех подшагов

### Ручное тестирование (полная регрессия)

1.Запустить плагин
        2.Создать новую сессию
3.Отправить несколько сообщений(API режим)
4.Проверить что токены считаются в заголовке
        5.Создать ветку от сессии
        6.Убедиться что дерево отображается корректно
7.Переключиться между сессиями
8.Проверить breadcrumb
        9.Переименовать сессию
        10.Удалить сессию (дети должны переподвеситься к родителю)
11.Каскадно удалить сессию с потомками
12.Добавить / удалить контекстные файлы
13.* * Перезапустить IDE * * — вся история сохранилась
        14.Проверить Clipboard режим — отправить сообщение через clipboard

### Компиляция после каждого подшага
```
./gradlew compileKotlin
```
