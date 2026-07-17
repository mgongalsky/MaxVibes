# STEP 3 — Топик prefill +вход в ChatPanel(plugin)

Цель: доставить текст рецепта в поле ввода чата, не трогая init огромного ChatPanel.kt.

## 3.1 Новый файл: топик
`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatInputListener.kt`

```kotlin
package com.maxvibes.plugin.ui

import com . intellij . util . messages . Topic

/**
 * Project-level message-bus topic: editor actions deliver a prepared message
 * into the MaxVibes chat input (prefill, not auto-send).
 */
interface ChatInputListener {
    fun onPrefillRequested(text: String)

    companion object {
        val TOPIC: Topic<ChatInputListener> =
            Topic.create("MaxVibes chat input prefill", ChatInputListener::class.java)
    }
}
```

## 3.2 Новый файл: публикатор
`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/action/ChatPrefill.kt`

```kotlin
package com.maxvibes.plugin.action

import com . intellij . openapi . project . Project
        import com . intellij . openapi . ui . Messages
        import com . intellij . openapi . wm . ToolWindowManager
        import com . maxvibes . plugin . ui . ChatInputListener

        /** Activates the MaxVibes tool window, then publishes the prefill text. EDT only. */
        object ChatPrefill {
            fun publish(project: Project, text: String) {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("MaxVibes")
                if (toolWindow == null) {
                    Messages.showWarningDialog(project, "MaxVibes tool window is not available", "MaxVibes")
                    return
                }
                toolWindow.activate({
                    project.messageBus.syncPublisher(ChatInputListener.TOPIC).onPrefillRequested(text)
                }, true)
            }
        }
```

## 3.3 ChatPanel : новый публичный метод (CREATE_ELEMENT, init не трогаем)

```kotlin
/**
 * Prefills the chat input with a prepared message (editor recipes).
 * Does NOT send — the user reviews, optionally edits, and presses Ctrl+Enter.
 * EDT only (invoked from the message-bus subscriber).
 */
fun prefillInput(text: String) {
    inputArea.text = text
    inputArea.caretPosition = text.length
    inputArea.requestFocusInWindow()
}
```

## 3.4 MaxVibesToolPanel : подписка в init
        Правка init → REPLACE_FILE на MaxVibesToolWindowFactory . kt (файл маленький).Перед правкой обязательно перезапросить его FULL (мог измениться).Добавить в init MaxVibesToolPanel :

```kotlin
project.messageBus.connect(chatPanel).subscribe(
    ChatInputListener.TOPIC,
    object : ChatInputListener {
        override fun onPrefillRequested(text: String) {
            SwingUtilities.invokeLater {
                showChat()
                chatPanel.prefillInput(text)
            }
        }
    }
)
```

connect(chatPanel) привязывает подписку к жизни панели (ChatPanel — Disposable, зарегистрирован на toolWindow.disposable) — утечек и дублей при пересоздании контента нет.

## Проверка шага
        Проект компилируется; поведение проверяется в STEP_5 после появления экшенов.
