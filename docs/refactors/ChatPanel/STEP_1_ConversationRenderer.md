# Step 1: Extract ConversationRenderer

## Контекст задачи

        Мы рефакторим `ChatPanel.kt` — God Object на 610 + строк в модуле `maxvibes-plugin` . Задача разбита на 6 независимых шагов.Этот шаг — первый и самый безопасный: мы только * * создаём новый файл * * и * * заменяем несколько строк * * в существующем.

### Архитектурный контекст

        Проект следует Clean Architecture :
-`maxvibes-domain` — доменные модели (`ChatMessage`, `MessageRole`, `TokenUsage`)
-`maxvibes-application` — use cases и порты
        -`maxvibes-adapter-psi`, `maxvibes-adapter-llm` — адаптеры
-`maxvibes-plugin` — IntelliJ UI, сервисы, действия

Все новые классы этого рефакторинга живут в `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/` .

### Что сейчас не так

        В `ChatPanel.kt` метод `loadCurrentSession()` содержит логику * * фильтрации сообщений для отображения * * :
-Пропускает сообщения с текстом `[Pasted LLM response]`
-Применяет regex -замены к тексту сообщений перед отображением
        -Решает какие сообщения показывать пользователю

Эта логика * * не должна жить во View * * — View не должен знать про внутренний формат хранения данных.

---

## Задача

### 1.Создать файл `ConversationRenderer.kt`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ConversationRenderer.kt`

```kotlin
package com.maxvibes.plugin.ui

import com . maxvibes . domain . model . chat . ChatMessage
        import com . maxvibes . domain . model . chat . MessageRole
        import com . maxvibes . domain . model . chat . TokenUsage

/**
 * Отвечает за преобразование списка ChatMessage в DisplayMessage для отображения в UI.
 * Инкапсулирует логику фильтрации системных сообщений и форматирования текста.
 */
class ConversationRenderer {

    /**
     * Преобразует список сообщений сессии в список отображаемых сообщений.
     * Фильтрует системные/технические сообщения, которые не должны видеть пользователи.
     */
    fun render(messages: List<ChatMessage>): List<DisplayMessage> {
        return messages
            .filter { shouldDisplay(it) }
            .map { message ->
                DisplayMessage(
                    role = message.role,
                    content = formatContent(message.content),
                    tokenUsage = message.tokenUsage
                )
            }
    }

    /**
     * Определяет, должно ли сообщение отображаться пользователю.
     */
    fun shouldDisplay(message: ChatMessage): Boolean {
        if (message.content.trim() == "[Pasted LLM response]") return false
        if (message.content.isBlank()) return false
        return true
    }

    /**
     * Форматирует текст сообщения для отображения.
     * Убирает технические артефакты из текста.
     */
    fun formatContent(content: String): String {
        return content
            .replace(Regex("\\[Pasted LLM response\\]\\n?"), "")
            .trim()
    }
}

/**
 * Сообщение, готовое к отображению в UI.
 */
data class DisplayMessage(
    val role: MessageRole,
    val content: String,
    val tokenUsage: TokenUsage? = null
)
```

### 2.Изменить `ChatPanel.kt`

        Найти в классе `ChatPanel` поле, где создаётся или используется логика фильтрации сообщений.

**Добавить поле * * в класс `ChatPanel`(рядом с другими private полями, после существующих полей):
```kotlin
private val conversationRenderer = ConversationRenderer()
```

**Найти метод `loadCurrentSession()` * * и заменить inline -логику фильтрации на вызов `conversationRenderer.render()`.До(
    примерный вид
):
```kotlin
val messages = session.messages
    .filter { it.content.trim() != "[Pasted LLM response]" }
    .filter { it.content.isNotBlank() }
    .map { message ->
        // форматирование контента
        message.copy(content = message.content.replace(Regex(...), "").trim())
    }
```

После:
```kotlin
val messages = conversationRenderer.render(session.messages)
```

При итерации по `messages` использовать `displayMessage.content` и `displayMessage.role` вместо полей `ChatMessage` напрямую . Если код дальше ожидает `ChatMessage` — можно оставить маппинг обратно или адаптировать вызывающий код.

### 3.Проверить компиляцию

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

Должно пройти без ошибок .

---

## Ручное тестирование

        После выполнения задачи запустить плагин(`./gradlew runIde`) и проверить:

-[] Открыть плагин — история старых сессий отображается корректно
-[] Отправить новое сообщение — ответ ассистента отображается
        -[] Убедиться, что строка `[Pasted LLM response]` * * не появляется * * в UI
-[] Переключиться между несколькими сессиями — история в каждой загружается правильно
-[] Создать новую сессию — приветственное сообщение показывается

        ---

## Автоматические тесты

**Создать файл : * * `maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/ui/ConversationRendererTest.kt`

```kotlin
package com.maxvibes.plugin.ui

import com . maxvibes . domain . model . chat . ChatMessage
        import com . maxvibes . domain . model . chat . MessageRole
        import org . junit . jupiter . api . Test
        import org . junit . jupiter . api . Assertions . *

class ConversationRendererTest {

    private val renderer = ConversationRenderer()

    @Test
    fun `render returns empty list for empty input`() {
        val result = renderer.render(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `render includes USER and ASSISTANT messages`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Hello"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Hi there")
        )
        val result = renderer.render(messages)
        assertEquals(2, result.size)
        assertEquals(MessageRole.USER, result[0].role)
        assertEquals(MessageRole.ASSISTANT, result[1].role)
    }

    @Test
    fun `render filters out Pasted LLM response messages`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Real message"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "[Pasted LLM response]"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Real response")
        )
        val result = renderer.render(messages)
        assertEquals(2, result.size)
        assertEquals("Real message", result[0].content)
        assertEquals("Real response", result[1].content)
    }

    @Test
    fun `render filters out blank messages`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Hello"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "   "),
            ChatMessage(role = MessageRole.ASSISTANT, content = "")
        )
        val result = renderer.render(messages)
        assertEquals(1, result.size)
    }

    @Test
    fun `shouldDisplay returns false for Pasted LLM response`() {
        val message = ChatMessage(role = MessageRole.ASSISTANT, content = "[Pasted LLM response]")
        assertFalse(renderer.shouldDisplay(message))
    }

    @Test
    fun `shouldDisplay returns false for blank content`() {
        val message = ChatMessage(role = MessageRole.USER, content = "  ")
        assertFalse(renderer.shouldDisplay(message))
    }

    @Test
    fun `shouldDisplay returns true for normal message`() {
        val message = ChatMessage(role = MessageRole.USER, content = "Normal message")
        assertTrue(renderer.shouldDisplay(message))
    }

    @Test
    fun `formatContent removes Pasted LLM response artifact`() {
        val content = "[Pasted LLM response]\nActual content here"
        val result = renderer.formatContent(content)
        assertEquals("Actual content here", result)
    }

    @Test
    fun `formatContent trims whitespace`() {
        val content = "  Hello world  "
        val result = renderer.formatContent(content)
        assertEquals("Hello world", result)
    }

    @Test
    fun `render preserves message order`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "First"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Second"),
            ChatMessage(role = MessageRole.USER, content = "Third")
        )
        val result = renderer.render(messages)
        assertEquals("First", result[0].content)
        assertEquals("Second", result[1].content)
        assertEquals("Third", result[2].content)
    }
}
```

Запустить тесты :
```bash
    ./ gradlew : maxvibes -plugin:test-- tests "com.maxvibes.plugin.ui.ConversationRendererTest"
```

---

## Коммит

```
refactor: extract ConversationRenderer from ChatPanel
```

## Следующий шаг

        После успешного выполнения и прохождения всех тестов — переходить к `STEP_2_InteractionModeManager.md`.
