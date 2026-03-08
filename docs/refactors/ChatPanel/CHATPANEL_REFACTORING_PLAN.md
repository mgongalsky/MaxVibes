# ChatPanel Refactoring Plan

## Цель

Разбить `ChatPanel` (God Object, 610 + строк) на несколько классов с чёткими ответственностями, следуя принципам Clean Architecture и SRP . После рефакторинга `ChatPanel` должен быть чистым View (~150–200 строк), делегирующим всю логику в специализированные классы .

## Целевая структура

```
plugin / ui /
├── ChatPanel.kt                  # View: только UI -компоненты и вызовы Presenter
├── ChatMessageController.kt      # Presenter: отправка сообщений, auto - retry(уже есть)
├── ConversationRenderer.kt       # НОВЫЙ: фильтрация и форматирование сообщений
├── InteractionModeManager.kt     # НОВЫЙ: state machine режимов API / Clipboard / CheapAPI
├── ChatPanelState.kt             # НОВЫЙ: data class состояния панели
├── SessionTreePanel.kt           # без изменений
├── ConversationPanel.kt          # без изменений
└── ...
```

## Принципы выполнения

        -* * Плагин компилируется и запускается после каждого шага * * — никаких незавершённых переходов
-* * Один шаг = одна ответственность * * — не смешивать изменения из разных шагов
        -* * Тесты пишутся в конце каждого шага * * — не в конце всего рефакторинга
        -* * Код не меняет поведение * * — только перемещение и инкапсуляция, никаких новых фич
-* * Коммит после каждого шага * * — удобно откатиться, если что -то пошло не так

        ---

## Шаг 1: Extract ConversationRenderer

### Что делаем

        Выносим логику * * фильтрации и форматирования сообщений для отображения * * из `ChatPanel` в отдельный класс `ConversationRenderer` .

Сейчас в `loadCurrentSession()` живёт такая логика :
-Пропуск сообщений `[Pasted LLM response]`
-Regex - замены в тексте сообщений
        -Решение о том, какие сообщения показывать пользователю

        Эта логика * * не должна жить в View * * — она знает про внутренний формат хранения данных.

### Файлы

**Создать:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ConversationRenderer.kt`

```kotlin
// Отвечает за одно: превратить список ChatMessage в список DisplayMessage
class ConversationRenderer {
    fun render(messages: List<ChatMessage>): List<DisplayMessage>
    fun shouldDisplay(message: ChatMessage): Boolean
    fun formatContent(content: String): String
}

data class DisplayMessage(
    val role: MessageRole,
    val content: String,
    val tokenUsage: TokenUsage?
)
```

**Изменить:** `ChatPanel.kt` — `loadCurrentSession()` теперь вызывает `ConversationRenderer.render()` вместо inline -логики.

### Ручное тестирование

        -[] Открыть плагин, история сообщений отображается корректно
        -[] `[Pasted LLM response]` не показывается в UI
        -[] Старые сессии из XML загружаются и отображаются без изменений
        -[] Форматирование текста(замены через regex) работает как раньше

### Автоматические тесты

        Файл: `maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/ui/ConversationRendererTest.kt`

-`render() пропускает [Pasted LLM response] сообщения`
-`render() возвращает USER и ASSISTANT сообщения`
-`formatContent() применяет regex-замены корректно`
-`render() на пустом списке возвращает пустой список`
-`shouldDisplay() возвращает false для системных сообщений`

### Коммит
```
refactor: extract ConversationRenderer from ChatPanel
```

---

## Шаг 2: Extract InteractionModeManager

### Что делаем

        Выносим * * state machine режимов взаимодействия * * в отдельный класс . Сейчас в `ChatPanel` живут:
-`switchMode(newMode: InteractionMode)`
-`syncModeFromSettings()`
-`updateModeIndicator()`
-Хранение текущего режима(`currentMode`)

Это независимая логика — она не зависит от Swing - компонентов.

### Файлы

**Создать:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/InteractionModeManager.kt`

```kotlin
class InteractionModeManager(
    private val settings: MaxVibesSettings,
    private val onModeChanged: (InteractionMode) -> Unit
) {
    var currentMode: InteractionMode
        private set

    fun switchMode(newMode: InteractionMode)
    fun syncFromSettings()  // читает настройки и обновляет режим
    fun isClipboardMode(): Boolean
    fun isApiMode(): Boolean
}
```

**Изменить:** `ChatPanel.kt` — создаёт `InteractionModeManager`, передаёт колбек для обновления UI - индикатора.`updateModeIndicator()` остаётся в ChatPanel, но вызывается через колбек .

### Ручное тестирование

        -[] Переключение режимов через кнопки работает
        -[] Режим из Settings применяется при открытии плагина
        -[] Индикатор режима в UI обновляется корректно
-[] CheapAPI режим включается / выключается

### Автоматические тесты

        Файл: `maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/ui/InteractionModeManagerTest.kt`

-`switchMode() переключает currentMode`
-`switchMode() вызывает колбек onModeChanged`
-`syncFromSettings() читает режим из Settings`
-`isClipboardMode() возвращает true только для CLIPBOARD режима`
-`switchMode() из CLIPBOARD в API меняет состояние`

### Коммит
```
refactor: extract InteractionModeManager from ChatPanel
```

---

## Шаг 3: Introduce ChatPanelState

### Что делаем

        Вводим * * data class состояния панели * * и метод `render(state)` в ChatPanel.Это подготовительный шаг — мы не переносим логику, только структурируем то, что уже есть.После этого шага ChatPanel умеет «рендерить состояние » — получает объект и обновляет все UI -компоненты по нему.

### Файлы

**Создать:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanelState.kt`

```kotlin
data class ChatPanelState(
    val currentSession: ChatSession?,
    val sessionPath: List<ChatSession>,
    val mode: InteractionMode,
    val isWaitingResponse: Boolean,
    val attachedTrace: String?,
    val attachedErrors: String?,
    val contextFilesCount: Int,
    val tokenUsage: TokenUsage?
)
```

**Изменить:** `ChatPanel.kt` — добавить `fun render(state: ChatPanelState)`, который обновляет все лейблы, кнопки, индикаторы.Все прямые обновления UI вынести в этот метод . `loadCurrentSession()` в конце вызывает `render(buildState())` .

### Ручное тестирование

        -[] Всё работает как раньше — визуально ничего не изменилось
        -[] При смене сессии UI обновляется
        -[] Индикаторы токенов, контекста, режима отображаются корректно

### Автоматические тесты

        Тесты для этого шага — интеграционные или ручные, unit - тесты для `ChatPanelState` сами по себе тривиальны.Основная ценность — что плагин не сломался.

-Проверить что `ChatPanelState` корректно сериализуется(если нужно)
-Snapshot - тест или assertion: после `render(state)` нужные компоненты имеют ожидаемые значения

### Коммит
```
refactor: introduce ChatPanelState and render () method in ChatPanel
```

---

## Шаг 4: Move Attachment Management to Controller

### Что делаем

        Переносим управление * * прикреплёнными данными * * (trace, IDE errors) из ChatPanel в ChatMessageController :
-`attachTraceFromClipboard()` → Controller
-`fetchIdeErrors()` → Controller, заменить `runBlocking` на `Task.Backgroundable`
        -`clearAttachedTrace()`, `clearAttachedErrors()` → Controller
-`updateIndicators()` → Controller вызывает колбек → ChatPanel обновляет UI

После этого шага в ChatPanel не останется ни одного вызова `executeOnPooledThread` или `runBlocking`.

### Файлы

**Изменить:** `ChatMessageController.kt`
-Добавить `attachedTrace: String?` и `attachedErrors: String?` как свойства
        -Перенести методы работы с attachments
-`fetchIdeErrors()` использует `Task.Backgroundable` вместо `runBlocking`
-Колбеки для уведомления View об изменениях

**Изменить:** `ChatPanel.kt`
-Убрать `attachedTrace`, `attachedErrors` поля
        -Кнопки вызывают `controller.attachTrace()`, `controller.fetchErrors()` и т.д.
-Controller уведомляет Panel через существующий `ChatPanelCallbacks`

### Ручное тестирование

        -[] Кнопка "Attach trace from clipboard" работает
        -[] Кнопка "Fetch IDE errors" работает, не зависает UI
-[] Прикреплённые данные отправляются вместе с сообщением
-[] Clear кнопки очищают attachments
-[] Индикаторы (иконки) обновляются при прикреплении / удалении

### Автоматические тесты

        Файл: дополнить `ChatMessageControllerTest.kt` (или создать если нет)

-`attachTrace() сохраняет переданный текст`
-`clearTrace() обнуляет attachedTrace`
-`fetchIdeErrors() вызывает ideErrorsPort`
-`buildFullTask() включает attachedTrace в текст задачи`
-`buildFullTask() включает attachedErrors в текст задачи`
-`clearAttachmentsAfterSend() вызывается после успешной отправки`

### Коммит
```
refactor: move attachment management from ChatPanel to ChatMessageController
```

---

## Шаг 5: Move Session Operations to Controller

### Что делаем

        Переносим * * операции с сессиями * * из ChatPanel в ChatMessageController :
-`deleteCurrentChat()` → Controller
-`startInlineRename()` → Controller
-`loadCurrentSession()`(бизнес - часть) → Controller, View - часть остаётся
        -`updateBreadcrumb()` → переходит в `render(state)`

После этого шага ChatPanel содержит только :
1.`setupUI()` — создание компонентов
        2.`render(state: ChatPanelState)` — обновление UI
        3.`setupListeners()` — маленькие лямбды -роутеры

### Файлы

**Изменить:** `ChatMessageController.kt`
-Добавить `deleteCurrentSession()`
        -Добавить `renameSession(newTitle: String)`
        -Добавить `loadSession(sessionId: String)`
        -После каждой операции уведомлять View через callback с новым `ChatPanelState`

**Изменить:** `ChatPanel.kt`
-`deleteCurrentChat()` → `controller.deleteCurrentSession()`
-`startInlineRename()` → остаётся в Panel(это UI -логика диалога), но сохранение → `controller.renameSession()`
        -`loadCurrentSession()` → `controller.loadSession()` + `render(state)`
-Лямбды в `setupListeners()` становятся однострочными

### Ручное тестирование

        -[] Создание новой сессии работает
-[] Удаление сессии работает
        -[] Переименование сессии работает
        -[] Навигация по дереву сессий работает
        -[] Branch (ответвление) сессии работает
-[] История загружается при открытии плагина

### Автоматические тесты

        -`deleteCurrentSession() вызывает chatTreeService.deleteNode()`
-`deleteCurrentSession() уведомляет View через callback`
-`loadSession() загружает правильную сессию`
-`loadSession() передаёт сообщения в ConversationRenderer`
-`renameSession() обновляет title в ChatTreeService`

### Коммит
```
refactor: move session operations from ChatPanel to ChatMessageController
```

---

## Шаг 6: Final Cleanup

### Что делаем

        Финальный cleanup — убираем технический долг, накопленный во время рефакторинга .

#### 6 a . Централизовать `toChatMessageDTO()`

Сейчас дублируется в 3 местах . Переносим в один extension - файл:

**Создать:** `maxvibes-adapter-llm/src/.../dto/ChatMessageDTOMapper.kt`
```kotlin
fun ChatMessage.toChatMessageDTO(): ChatMessageDTO
fun List<ChatMessage>.toChatMessageDTOs(): List<ChatMessageDTO>
```

Удалить дубликаты из `ChatPanel`, `ChatMessageController`, `ChatHistoryService`.

#### 6 b . Удалить `src/` в корне проекта

        Удалить папку `src/main/kotlin/` в корне — это артефакт предыдущего рефакторинга :
-`STEP_1_MessageRole.md` ... `STEP_9_Cleanup.md`
-`IntellijIdeErrorsAdapter.kt`(уже переехал в `maxvibes-adapter-psi`)

#### 6 c . Обновить документацию

Обновить `docs/ARCHITECTURE_RESEARCH.md` и `docs/CURRENT_STATUS.md` — отразить новую структуру .

### Ручное тестирование

        -[] Полный smoke test : отправить сообщение в API режиме
        -[] Полный smoke test : отправить сообщение в Clipboard режиме
        -[] Создать, переименовать, удалить сессию
        -[] Прикрепить trace, прикрепить ошибки, отправить
-[] Переключить режимы
-[] Перезапустить IDE — история сохраняется

### Автоматические тесты

        -`toChatMessageDTO() маппит USER → ChatRole.USER`
-`toChatMessageDTO() маппит ASSISTANT → ChatRole.ASSISTANT`
-`toChatMessageDTOs() конвертирует список корректно`
-Убедиться что все существующие тесты проходят (`./gradlew test`)

### Коммит
```
refactor: cleanup ChatPanel refactoring — centralize DTOs, remove stale files
```

---

## Итоговая таблица шагов

| Шаг | Что делаем | Новые файлы | Сложность | ~Часов |
|-----|---------- - |------------ - |---------- - |--------|
| 1 | ConversationRenderer | ConversationRenderer.kt | Низкая | 2–3 |
| 2 | InteractionModeManager | InteractionModeManager.kt | Низкая | 2–3 |
| 3 | ChatPanelState + render() | ChatPanelState.kt | Средняя | 3–4 |
| 4 | Attachment → Controller | — | Средняя | 4–5 |
| 5 | Session ops → Controller | — | Высокая | 5–6 |
| 6 | Final cleanup | ChatMessageDTOMapper . kt | Низкая | 2–3 |

**Итого:** ~18–24 часа работы джуниора .

## Важные ограничения

        1.* * Не трогать `ChatTreeService` * * — он уже в правильном месте(application layer)
2.* * Не трогать `ChatHistoryService` * * — чистый persistence adapter
3.* * `ChatMessageController` * * — расширяем, не переписываем с нуля
        4.* * XML - совместимость * * — не меняем формат сохранения сессий
5.* * Шаги строго последовательны * * — нельзя делать шаг 4 до завершения шага 3
