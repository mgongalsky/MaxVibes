# Step 3: Introduce ChatPanelState and render ()

## Контекст задачи

        Это третий шаг рефакторинга `ChatPanel.kt`.Шаги 1 и 2 уже выполнены:
-`ConversationRenderer` создан и используется
        -`InteractionModeManager` создан и используется

### Что делаем на этом шаге

Вводим * * data class состояния панели * * и метод `render(state)`.Это подготовительный шаг к полноценному MVP — мы не переносим логику, только структурируем то, что уже есть.

**Цель:** после этого шага `ChatPanel` умеет принять объект состояния и обновить весь UI по нему . Это фундамент для шагов 4 и 5, где Controller начнёт управлять состоянием.

### Почему это важно

Сейчас в `ChatPanel` разбросаны десятки прямых обновлений UI :
```kotlin
statusLabel.text = "Waiting..."
tokenLabel.text = "${session.totalTokens} tokens"
breadcrumbLabel.text = session.title
```

После этого шага все обновления UI будут проходить через одну точку — `render(state)`.Это делает View предсказуемым и тестируемым .

---

## Задача

### 1.Создать файл `ChatPanelState.kt`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanelState.kt`

```kotlin
package com.maxvibes.plugin.ui

import com . maxvibes . domain . model . chat . ChatSession
        import com . maxvibes . domain . model . chat . TokenUsage
        import com . maxvibes . domain . model . interaction . InteractionMode

        /**
         * Полное состояние ChatPanel в один момент времени.
         * ChatPanel.render(state) читает этот объект и обновляет все UI-компоненты.
         */
        data class ChatPanelState(
    /** Текущая активная сессия. Null если сессий нет. */
    val currentSession: ChatSession?,

    /** Путь от корня до текущей сессии (хлебные крошки). */
    val sessionPath: List<ChatSession> = emptyList(),

    /** Текущий режим взаимодействия. */
    val mode: InteractionMode = InteractionMode.API,

    /** true пока идёт запрос к LLM — блокирует кнопку отправки. */
    val isWaitingResponse: Boolean = false,

    /** Прикреплённый трейс (текст из буфера обмена). Null если не прикреплён. */
    val attachedTrace: String? = null,

    /** Прикреплённые ошибки IDE. Null если не прикреплены. */
    val attachedErrors: String? = null,

    /** Количество файлов контекста. */
    val contextFilesCount: Int = 0,

    /** Использование токенов текущей сессии. */
    val tokenUsage: TokenUsage? = null
) {
    /** true если есть прикреплённые данные любого типа. */
    val hasAttachments: Boolean get() = attachedTrace != null || attachedErrors != null
}
```

### 2.Добавить метод `render()` в `ChatPanel.kt`

В класс `ChatPanel` добавить метод `render(state: ChatPanelState)`, который обновляет все UI -компоненты на основе состояния .

Найти все места в `ChatPanel`, где напрямую обновляются лейблы, кнопки, индикаторы — и перенести эти обновления в `render()` .

**Примерный вид метода:**
```kotlin
fun render(state: ChatPanelState) {
    // Хлебные крошки / заголовок
    updateBreadcrumb(state.sessionPath, state.currentSession)

    // Индикатор режима
    updateModeIndicator(state.mode)

    // Кнопка отправки
    sendButton.isEnabled = !state.isWaitingResponse && state.currentSession != null
    inputField.isEnabled = !state.isWaitingResponse

    // Индикаторы прикреплений
    traceIndicator.isVisible = state.attachedTrace != null
    errorsIndicator.isVisible = state.attachedErrors != null

    // Токены
    updateTokenDisplay(state.tokenUsage)

    // Количество файлов контекста
    updateContextIndicator(state.contextFilesCount)
}
```

> Адаптируй под реальные имена компонентов в `ChatPanel`.Смотри `setupUI()` чтобы увидеть реальные имена полей.

### 3.Добавить вспомогательный метод `buildState()` в `ChatPanel`

        Этот метод собирает текущее состояние из полей ChatPanel :

```kotlin
private fun buildState(): ChatPanelState {
    return ChatPanelState(
        currentSession = currentSession,  // существующее поле
        sessionPath = currentSessionPath, // существующее поле (если есть)
        mode = modeManager.currentMode,
        isWaitingResponse = isWaitingForResponse, // существующее поле
        attachedTrace = attachedTrace,    // существующее поле
        attachedErrors = attachedErrors,  // существующее поле
        contextFilesCount = contextFilesCount, // существующее поле (если есть)
        tokenUsage = currentSession?.tokenUsage
    )
}
```

### 4.Использовать `render(buildState())` в конце ключевых методов

        В конец методов `loadCurrentSession()`, `switchMode()`(или в колбек `onModeChanged`), и других методов, меняющих состояние UI — добавить вызов :
```kotlin
render(buildState())
```

### 5.Проверить компиляцию

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

---

## Ручное тестирование

        -[] Визуально плагин выглядит и работает так же, как до изменений
-[] При переключении между сессиями UI обновляется корректно
        -[] Индикаторы токенов отображаются верно
-[] Кнопка отправки блокируется во время ожидания ответа
        -[] После получения ответа кнопка снова активна
-[] Индикаторы прикреплённых файлов / ошибок работают

---

## Автоматические тесты

        `ChatPanelState` — простой data class, unit -тесты для него тривиальны, но полезны как документация :

**Создать файл : * * `maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/ui/ChatPanelStateTest.kt`

```kotlin
package com.maxvibes.plugin.ui

import com . maxvibes . domain . model . interaction . InteractionMode
        import org . junit . jupiter . api . Test
        import org . junit . jupiter . api . Assertions . *

class ChatPanelStateTest {

    @Test
    fun `default state has no session`() {
        val state = ChatPanelState(currentSession = null)
        assertNull(state.currentSession)
    }

    @Test
    fun `default mode is API`() {
        val state = ChatPanelState(currentSession = null)
        assertEquals(InteractionMode.API, state.mode)
    }

    @Test
    fun `hasAttachments is false when no attachments`() {
        val state = ChatPanelState(currentSession = null)
        assertFalse(state.hasAttachments)
    }

    @Test
    fun `hasAttachments is true when trace attached`() {
        val state = ChatPanelState(currentSession = null, attachedTrace = "some trace")
        assertTrue(state.hasAttachments)
    }

    @Test
    fun `hasAttachments is true when errors attached`() {
        val state = ChatPanelState(currentSession = null, attachedErrors = "some errors")
        assertTrue(state.hasAttachments)
    }

    @Test
    fun `isWaitingResponse defaults to false`() {
        val state = ChatPanelState(currentSession = null)
        assertFalse(state.isWaitingResponse)
    }

    @Test
    fun `copy creates modified state without changing original`() {
        val original = ChatPanelState(currentSession = null, isWaitingResponse = false)
        val waiting = original.copy(isWaitingResponse = true)
        assertFalse(original.isWaitingResponse)
        assertTrue(waiting.isWaitingResponse)
    }
}
```

Запустить тесты :
```bash
    ./ gradlew : maxvibes -plugin:test-- tests "com.maxvibes.plugin.ui.ChatPanelStateTest"
    ./ gradlew : maxvibes -plugin:test-- tests "com.maxvibes.plugin.ui.ConversationRendererTest"
    ./ gradlew : maxvibes -plugin:test-- tests "com.maxvibes.plugin.ui.InteractionModeManagerTest"
```

Все три теста из предыдущих шагов тоже должны проходить.

---

## Коммит

```
refactor: introduce ChatPanelState and render () method
```

## Следующий шаг

        `STEP_4_AttachmentManagement.md`
