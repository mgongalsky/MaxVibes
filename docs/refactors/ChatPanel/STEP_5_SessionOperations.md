# Step 5: Move Session Operations to Controller

## Контекст задачи

        Это пятый и самый крупный шаг . Шаги 1–4 выполнены . После этого шага `ChatPanel` станет тонким View .

### Что делаем

        Переносим * * операции с сессиями * * из `ChatPanel` в `ChatMessageController` :
-`deleteCurrentChat()` / `deleteCurrentSession()`
-`startInlineRename()` — UI - часть остаётся в Panel, сохранение → Controller
-`loadCurrentSession()` — бизнес - часть переезжает, View - часть через `render()`
-Логику создания новой сессии ("New Chat" кнопка)
-Логику ветвления сессии("Branch" кнопка)

### Важный архитектурный момент

`ChatTreeService` — application - сервис, который уже делает всю бизнес - логику с деревом сессий . Controller должен * * вызывать `ChatTreeService` * *(
    через `MaxVibesService`
), а не дублировать логику .

Посмотри `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ChatTreeService.kt` перед началом .

---

## Задача

### 1.Добавить методы в `ChatMessageController.kt`

**Метод `createNewSession()` : * *
```kotlin
fun createNewSession() {
    val newSession = service.chatTreeService.createSession()
    service.chatSessionRepository.save(newSession)
    callbacks.onSessionChanged(newSession)
}
```

**Метод `deleteCurrentSession()` : * *
```kotlin
fun deleteCurrentSession(sessionId: String) {
    service.chatTreeService.deleteNode(sessionId)
    val nextSession = service.chatTreeService.getRootSessions().firstOrNull()
    callbacks.onSessionChanged(nextSession)
    if (nextSession == null) {
        callbacks.onShowWelcome()
    }
}
```

**Метод `renameSession()` : * *
```kotlin
fun renameSession(sessionId: String, newTitle: String) {
    val updated = service.chatTreeService.renameSession(sessionId, newTitle)
    callbacks.onSessionRenamed(updated)
}
```

**Метод `branchSession()` : * *
```kotlin
fun branchSession(parentSessionId: String, title: String) {
    val newSession = service.chatTreeService.createBranch(parentSessionId, title)
    service.chatSessionRepository.save(newSession)
    callbacks.onSessionChanged(newSession)
}
```

**Метод `loadSession()` : * *
```kotlin
fun loadSession(sessionId: String) {
    val session = service.chatSessionRepository.findById(sessionId)
    if (session != null) {
        callbacks.onSessionChanged(session)
    }
}
```

> Адаптируй под реальные методы `ChatTreeService` и `ChatSessionRepository`.Смотри их интерфейсы в `maxvibes-application/src/main/kotlin/com/maxvibes/application/`.

### 2.Расширить интерфейс `ChatPanelCallbacks`

Добавить новые методы обратной связи:
```kotlin
interface ChatPanelCallbacks {
    // ... существующие методы ...
    fun onSessionChanged(session: ChatSession?)
    fun onSessionRenamed(session: ChatSession)
    fun onShowWelcome()
}
```

### 3.Реализовать новые callbacks в `ChatPanel`

```kotlin
override fun onSessionChanged(session: ChatSession?) {
    currentSession = session
    render(buildState())
    if (session != null) {
        displayMessages(session)
    }
}

override fun onSessionRenamed(session: ChatSession) {
    if (currentSession?.id == session.id) {
        currentSession = session
    }
    render(buildState())
}

override fun onShowWelcome() {
    showWelcome()
}
```

### 4.Обновить лямбды в `setupListeners()` в `ChatPanel`

        Заменить большие лямбды на однострочные вызовы controller:

```kotlin
// Было — большая лямбда:
newChatButton.addActionListener {
    resetClipboard()
    chatTreeService.createNewSession()
    clearAttachedTrace()
    clearAttachedErrors()
    loadCurrentSession()
    updateModeIndicator()
    statusLabel.text = "New dialog"
}

// Стало:
newChatButton.addActionListener { controller.createNewSession() }
```

```kotlin
// deleteButton:
deleteButton.addActionListener {
    currentSession?.id?.let { controller.deleteCurrentSession(it) }
}
```

```kotlin
// branchButton (если есть):
branchButton.addActionListener {
    currentSession?.id?.let { id ->
        val title = showBranchTitleDialog() // диалог остаётся в Panel
        if (title != null) controller.branchSession(id, title)
    }
}
```

### 5.`startInlineRename()` — разделить UI и логику

        UI - часть(показать input field) остаётся в `ChatPanel` .
Сохранение нового названия — через controller :

```kotlin
private fun startInlineRename() {
    // UI: показать поле для ввода названия
    val dialog = createRenameTitleInput(currentSession?.title ?: "")
    if (dialog.showAndGet()) {
        val newTitle = dialog.newTitle
        currentSession?.id?.let { controller.renameSession(it, newTitle) }
    }
}
```

### 6.Удалить из `ChatPanel.kt`

-Поле `currentSession` (оно хранится через callback, панель получает его через `onSessionChanged`)
-Метод `deleteCurrentChat()` — заменён на `controller.deleteCurrentSession()`
        -Бизнес - логику внутри `loadCurrentSession()` — остаётся только `render(buildState())` и `displayMessages()`
-Прямые вызовы `chatTreeService` из ChatPanel

### 7.Компиляция

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

---

## Ручное тестирование

        -[] Кнопка "New Chat" создаёт новую сессию
        -[] Кнопка "Delete" удаляет текущую сессию
        -[] После удаления переключается на другую сессию или показывает welcome
        -[] Переименование сессии сохраняется и отображается
        -[] Branch (ответвление) создаёт дочернюю сессию
        -[] Клик по сессии в дереве — загружается правильная история
        -[] Перезапустить IDE — все сессии восстанавливаются
-[] Хлебные крошки(breadcrumbs) показывают правильный путь

        ---

## Автоматические тесты

**Создать файл : * * `maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/ui/ChatMessageControllerSessionTest.kt`

```kotlin
package com.maxvibes.plugin.ui

import com . maxvibes . domain . model . chat . ChatSession
        import io . mockk . *
        import org . junit . jupiter . api . Test
        import org . junit . jupiter . api . Assertions . *

class ChatMessageControllerSessionTest {

    // Замокать зависимости
    // private val mockService = mockk<MaxVibesService>(relaxed = true)
    // private val callbacks = mockk<ChatPanelCallbacks>(relaxed = true)
    // private val controller = ChatMessageController(mockService, callbacks)

    @Test
    fun `createNewSession calls chatTreeService createSession`() {
        // verify { mockService.chatTreeService.createSession() }
    }

    @Test
    fun `createNewSession calls onSessionChanged callback`() {
        // controller.createNewSession()
        // verify { callbacks.onSessionChanged(any()) }
    }

    @Test
    fun `deleteCurrentSession calls chatTreeService deleteNode`() {
        // controller.deleteCurrentSession("session-id")
        // verify { mockService.chatTreeService.deleteNode("session-id") }
    }

    @Test
    fun `deleteCurrentSession calls onShowWelcome when no sessions left`() {
        // every { mockService.chatTreeService.getRootSessions() } returns emptyList()
        // controller.deleteCurrentSession("session-id")
        // verify { callbacks.onShowWelcome() }
    }

    @Test
    fun `renameSession calls chatTreeService renameSession`() {
        // controller.renameSession("session-id", "New Title")
        // verify { mockService.chatTreeService.renameSession("session-id", "New Title") }
    }

    @Test
    fun `renameSession calls onSessionRenamed callback`() {
        // controller.renameSession("session-id", "New Title")
        // verify { callbacks.onSessionRenamed(any()) }
    }

    @Test
    fun `branchSession creates new branch via chatTreeService`() {
        // controller.branchSession("parent-id", "Branch Title")
        // verify { mockService.chatTreeService.createBranch("parent-id", "Branch Title") }
    }

    @Test
    fun `loadSession calls onSessionChanged with loaded session`() {
        // val session = ChatSession(id = "s1", title = "Test")
        // every { mockService.chatSessionRepository.findById("s1") } returns session
        // controller.loadSession("s1")
        // verify { callbacks.onSessionChanged(session) }
    }
}
```

> Раскомментировать и адаптировать под реальный API .

Запустить все тесты:
```bash
    ./ gradlew : maxvibes -plugin:test
```

---

## Коммит

```
refactor: move session operations from ChatPanel to ChatMessageController
```

## Следующий шаг

        `STEP_6_Cleanup.md`
