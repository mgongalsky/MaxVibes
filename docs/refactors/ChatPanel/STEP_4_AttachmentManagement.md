# Step 4: Move Attachment Management to Controller

## Контекст задачи

        Это четвёртый шаг.Шаги 1–3 выполнены :
-`ConversationRenderer` — создан
-`InteractionModeManager` — создан
-`ChatPanelState` + `render()` — введены

### Что делаем

        Переносим * * управление прикреплёнными данными * * из `ChatPanel` в `ChatMessageController` :
-`attachTraceFromClipboard()` — прикрепление трейса из буфера обмена
-`fetchIdeErrors()` — получение ошибок компилятора из IDE
-`clearAttachedTrace()` — очистка трейса
        -`clearAttachedErrors()` — очистка ошибок
        -Поля `attachedTrace: String?` и `attachedErrors: String?`

**Ключевое:** убираем `runBlocking` и `executeOnPooledThread` из `ChatPanel` . После этого шага в ChatPanel не останется ни одного вызова `executeOnPooledThread` .

### Текущая архитектура ChatMessageController

Посмотри файл `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatMessageController.kt` перед началом.Controller уже содержит:
-Ссылку на `MaxVibesService`(через который доступен `ideErrorsPort`)
-Интерфейс `ChatPanelCallbacks` для обратной связи с View
-Паттерн `Task.Backgroundable` для фоновых операций

---

## Задача

### 1.Добавить поля и методы в `ChatMessageController.kt`

        Добавить * * поля * * для хранения прикреплённых данных :
```kotlin
var attachedTrace: String? = null
    private set

var attachedErrors: String? = null
    private set
```

Добавить * * метод `attachTrace()` * *:
```kotlin
fun attachTrace(traceContent: String) {
    attachedTrace = traceContent
    callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
}
```

Добавить * * метод `clearTrace()` * *:
```kotlin
fun clearTrace() {
    attachedTrace = null
    callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
}
```

Добавить * * метод `clearErrors()` * *:
```kotlin
fun clearErrors() {
    attachedErrors = null
    callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
}
```

Добавить * * метод `fetchIdeErrors()` * * — заменить `runBlocking` на `Task.Backgroundable` :
```kotlin
fun fetchIdeErrors() {
    object : Task.Backgroundable(service.project, "Fetching IDE errors", false) {
        override fun run(indicator: ProgressIndicator) {
            val result = service.ideErrorsPort.getCompilerErrors()
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is Result.Success -> {
                        attachedErrors = result.value.joinToString("\n") { it.message }
                        callbacks.onAttachmentsChanged(attachedTrace, attachedErrors)
                    }

                    is Result.Failure -> {
                        callbacks.onError("Failed to fetch IDE errors: ${result.error}")
                    }
                }
            }
        }
    }.queue()
}
```

> Адаптируй под реальные типы `Result`, `IdeError` и метод `ideErrorsPort.getCompilerErrors()` . Смотри `IdeErrorsPort.kt` в `application/port/output/`.Добавить * * метод `clearAttachmentsAfterSend()` * *(
    вызывается после успешной отправки сообщения
):
```kotlin
fun clearAttachmentsAfterSend() {
    attachedTrace = null
    attachedErrors = null
    callbacks.onAttachmentsChanged(null, null)
}
```

### 2.Расширить интерфейс `ChatPanelCallbacks`

Найти интерфейс `ChatPanelCallbacks`(он может быть в `ChatMessageController.kt` или отдельным файлом) и добавить метод :

```kotlin
fun onAttachmentsChanged(trace: String?, errors: String?)
```

### 3.Реализовать `onAttachmentsChanged` в `ChatPanel`

        В `ChatPanel` найти реализацию `ChatPanelCallbacks` и добавить:
```kotlin
override fun onAttachmentsChanged(trace: String?, errors: String?) {
    // Обновить ChatPanelState через buildState() и render()
    // trace и errors теперь берутся из controller, а не из полей ChatPanel
    render(buildState())
}
```

Обновить `buildState()` чтобы он брал attachments из controller :
```kotlin
private fun buildState(): ChatPanelState {
    return ChatPanelState(
        // ...
        attachedTrace = controller.attachedTrace,
        attachedErrors = controller.attachedErrors,
        // ...
    )
}
```

### 4.Обновить `ChatPanel.kt` — делегировать в controller

        Кнопки прикрепления теперь вызывают методы controller :

```kotlin
// Было:
attachTraceButton.addActionListener { attachTraceFromClipboard() }

// Стало:
attachTraceButton.addActionListener {
    val clipboardContent = getClipboardContent() // локальная функция чтения буфера
    controller.attachTrace(clipboardContent)
}
```

```kotlin
// Было:
fetchErrorsButton.addActionListener { fetchIdeErrors() }

// Стало:
fetchErrorsButton.addActionListener { controller.fetchIdeErrors() }
```

```kotlin
// Было:
clearTraceButton.addActionListener { clearAttachedTrace() }

// Стало:
clearTraceButton.addActionListener { controller.clearTrace() }
```

### 5.Удалить из `ChatPanel.kt`

-Поля `attachedTrace` и `attachedErrors`
        -Методы `attachTraceFromClipboard()`, `fetchIdeErrors()`, `clearAttachedTrace()`, `clearAttachedErrors()`
-Весь код с `executeOnPooledThread` и `runBlocking` (если они были только для этих операций)

### 6.Убедиться что отправка сообщения включает attachments

        В методах отправки в `ChatMessageController`(или в `sendApiMessage` / `sendClipboardMessage` в ChatPanel — смотри текущий код) убедиться что `attachedTrace` и `attachedErrors` включаются в `fullTask` .

После успешной отправки вызвать `clearAttachmentsAfterSend()`.

### 7.Компиляция

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

---

## Ручное тестирование

        -[] Кнопка « Attach trace from clipboard» прикрепляет текст из буфера — индикатор появляется
-[] Кнопка « Fetch IDE errors » запускает фоновую задачу — UI не зависает
        -[] После fetchIdeErrors ошибки прикреплены — индикатор появляется
        -[] Clear кнопки убирают прикреплённые данные — индикаторы исчезают
-[] Отправить сообщение с прикреплённым трейсом — он включается в текст запроса
        -[] После отправки attachments очищаются автоматически
        -[] В режиме Clipboard attachments тоже работают

---

## Автоматические тесты

**Создать / дополнить файл : * * `maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/ui/ChatMessageControllerAttachmentTest.kt`

```kotlin
package com.maxvibes.plugin.ui

import io . mockk . *
        import org . junit . jupiter . api . Test
        import org . junit . jupiter . api . Assertions . *
        import org . junit . jupiter . api . BeforeEach

class ChatMessageControllerAttachmentTest {

    private val callbacks = mockk<ChatPanelCallbacks>(relaxed = true)
    // Создание controller — адаптировать под реальный конструктор
    // private val controller = ChatMessageController(mockService, callbacks)

    @Test
    fun `attachTrace stores trace content`() {
        // controller.attachTrace("some trace")
        // assertEquals("some trace", controller.attachedTrace)
    }

    @Test
    fun `attachTrace calls onAttachmentsChanged`() {
        // controller.attachTrace("some trace")
        // verify { callbacks.onAttachmentsChanged("some trace", null) }
    }

    @Test
    fun `clearTrace sets attachedTrace to null`() {
        // controller.attachTrace("some trace")
        // controller.clearTrace()
        // assertNull(controller.attachedTrace)
    }

    @Test
    fun `clearErrors sets attachedErrors to null`() {
        // controller.attachedErrors = "some errors" // если сеттер доступен для теста
        // controller.clearErrors()
        // assertNull(controller.attachedErrors)
    }

    @Test
    fun `clearAttachmentsAfterSend clears both attachments`() {
        // controller.attachTrace("trace")
        // controller.clearAttachmentsAfterSend()
        // assertNull(controller.attachedTrace)
        // assertNull(controller.attachedErrors)
    }

    @Test
    fun `clearAttachmentsAfterSend calls onAttachmentsChanged with nulls`() {
        // controller.clearAttachmentsAfterSend()
        // verify { callbacks.onAttachmentsChanged(null, null) }
    }
}
```

> Раскомментировать и адаптировать тесты под реальный API контроллера . Конструктор и зависимости — смотреть в `ChatMessageController.kt` .

Запустить все тесты:
```bash
    ./ gradlew : maxvibes -plugin:test
```

---

## Коммит

```
refactor: move attachment management from ChatPanel to ChatMessageController
```

## Следующий шаг

        `STEP_5_SessionOperations.md`
