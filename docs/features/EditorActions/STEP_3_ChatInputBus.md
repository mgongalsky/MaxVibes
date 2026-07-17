# STEP 3 — EditorPrefill-шина, one-shot скилл, багфикс билдера

Цель: доставка префила в чат + механика one-shot в контроллере + фикс потери attachedContext в minimal-режиме.

## 3.1 Новый файл: пейлоад + топик
`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatInputListener.kt`

```kotlin
package com.maxvibes.plugin.ui

import com.intellij.util.messages.Topic

/** Payload published by editor actions into the chat panel. */
data class EditorPrefill(
val text: String,
/** Append to the current input instead of replacing it (Add Element Reference). */
val append: Boolean = false,
/** One-shot skill armed for the next send; null = no skill (utility attach). */
val oneShotSkillName: String? = null,
/** Rendered element body attached as one-shot context; null = nothing to attach. */
val elementContext: String? = null,
/** Short chip label, e.g. "function validate". */
val elementLabel: String? = null
)

interface ChatInputListener {
fun onPrefill(prefill: EditorPrefill)

companion object {
val TOPIC: Topic<ChatInputListener> =
Topic.create("MaxVibes chat prefill", ChatInputListener::class.java)
}
}
```

## 3.2 Новый файл: публикатор
`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/action/ChatPrefill.kt`

```kotlin
package com.maxvibes.plugin.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.maxvibes.plugin.ui.ChatInputListener
import com.maxvibes.plugin.ui.EditorPrefill

/** Activates the MaxVibes tool window, then publishes the prefill. EDT only. */
object ChatPrefill {
fun publish(project: Project, prefill: EditorPrefill) {
val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("MaxVibes")
if (toolWindow == null) {
Messages.showWarningDialog(project, "MaxVibes tool window is not available", "MaxVibes")
return
}
toolWindow.activate({
project.messageBus.syncPublisher(ChatInputListener.TOPIC).onPrefill(prefill)
}, true)
}
}
```

## 3.3 ChatPanel: acceptPrefill (CREATE_ELEMENT, init не трогаем)

```kotlin
/**
* Accepts a prefill from an editor action: fills or appends the input and arms
* a one-shot skill/context when present. Does NOT send. EDT only.
*/
fun acceptPrefill(prefill: EditorPrefill) {
if (prefill.append && inputArea.text.isNotBlank()) {
inputArea.text = inputArea.text.trimEnd() + " " + prefill.text
} else if (prefill.text.isNotBlank()) {
inputArea.text = prefill.text
}
inputArea.caretPosition = inputArea.text.length
inputArea.requestFocusInWindow()
if (prefill.oneShotSkillName != null || prefill.elementContext != null) {
messageController.armOneShot(
prefill.oneShotSkillName, prefill.elementContext, prefill.elementLabel ?: "element"
)
}
}
```

Плюс реализация нового колбэка onOneShotChanged(label): чип «⚡ Skill: <label> (1×)» в attachmentsPanel с кнопкой ✕ → messageController.clearOneShot(). Перед правкой запросить ELEMENT текущих onAttachmentsChanged/updateIndicators и встроиться в их паттерн (детали видимости панели там).

## 3.4 ChatMessageController: one-shot
CREATE_ELEMENT (класс-holder + поле + два метода):

```kotlin
/** One-shot editor-skill invocation armed by acceptPrefill; consumed by the next send. */
private class PendingOneShot(val skillName: String?, val elementContext: String?, val label: String)
```

```kotlin
private var pendingOneShot: PendingOneShot? = null
```

```kotlin
fun armOneShot(skillName: String?, elementContext: String?, label: String) {
pendingOneShot = PendingOneShot(skillName, elementContext, label)
callbacks.onOneShotChanged(label)
}
```

```kotlin
fun clearOneShot() {
pendingOneShot = null
callbacks.onOneShotChanged(null)
}
```

В интерфейс ChatPanelCallbacks — CREATE_ELEMENT:
```kotlin
/** Shows/hides the one-shot skill chip; null label hides it. */
fun onOneShotChanged(label: String?)
```

REPLACE_ELEMENT на sendMessage: в начале, сразу после снятия trace/errs:
```kotlin
val oneShot = pendingOneShot
val effectivePromptName = oneShot?.skillName ?: selectedSpecificPromptName
val effectiveTrace = listOfNotNull(
oneShot?.elementContext?.let { "--- Element context ---\n" + it },
trace
).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
```
Далее по телу: во все dispatch-вызовы вместо trace передаётся effectiveTrace, вместо selectedSpecificPromptName — effectivePromptName. Если oneShot != null и mode == API — предупреждение в чат: «editor skill fully works in Clipboard/Claude Code; API mode gets the prefill text only». (CheapAPI встраивает effectiveTrace в текст через buildTaskWithContext — контекст доедет, скилл-промпт нет.)

REPLACE_ELEMENT на clearAttachmentsAfterSend — добавить:
```kotlin
pendingOneShot = null
callbacks.onOneShotChanged(null)
```

REPLACE_ELEMENT на approve() — паттерн как с images: если pendingOneShot != null → appendToChat("⚠️ one-shot skill dropped — invoke it with a regular message, not Approve") (clearAttachmentsAfterSend там уже вызывается и всё сбросит).

## 3.5 БАГФИКС: InteractionRequestBuilder
Строка `attachedContext = if (isMinimal) null else attachedContext,` заменяется на:
```kotlin
// attachedContext: one-shot per-message context — ALWAYS forwarded when provided,
// like ideErrors and commandResults. The old minimal-mode nulling silently dropped
// traces (and now element attachments) added mid-session in ClaudeCode/Clipboard.
attachedContext = attachedContext,
```
Тест в тесты билдера (Gradle): minimal-режим (isFirstMessage=false, addHistory=false) сохраняет attachedContext в ClipboardRequest.

## 3.6 MaxVibesToolPanel: подписка
REPLACE_FILE на MaxVibesToolWindowFactory.kt (правка init; перед правкой ОБЯЗАТЕЛЬНО перезапросить FULL — файл мог измениться). В init MaxVibesToolPanel:

```kotlin
project.messageBus.connect(chatPanel).subscribe(
ChatInputListener.TOPIC,
object : ChatInputListener {
override fun onPrefill(prefill: EditorPrefill) {
SwingUtilities.invokeLater {
showChat()
chatPanel.acceptPrefill(prefill)
}
}
}
)
```
connect(chatPanel) привязывает подписку к жизни панели (ChatPanel — Disposable) — нет утечек при пересоздании контента.

## Проверка шага
Проект компилируется; тест билдера зелёный (`gradlew.bat :maxvibes-application:test`). Поведение — STEP_6.
