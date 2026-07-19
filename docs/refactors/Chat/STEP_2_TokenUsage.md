# Шаг 2: Создать `TokenUsage` в domain

## Контекст

Сейчас счётчики токенов разбросаны по полям `ChatSession` в виде четырёх отдельных `Int` переменных(
    `planningInputTokens`,
    `planningOutputTokens`,
    `chatInputTokens`,
    `chatOutputTokens`
).Логика форматирования и подсчёта стоимости тоже живёт прямо в `ChatSession` .

На этом шаге мы создаём самостоятельный value object `TokenUsage` в domain . Старый код в `ChatSession` пока не трогаем — новый класс создаётся параллельно.

**Предварительное условие : * * Шаг 1 выполнен (`MessageRole` уже в domain).

## Что нужно сделать

### 1.Создать `TokenUsage.kt` в domain

**Путь:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/TokenUsage.kt`

```kotlin
package com.maxvibes.domain.model.chat

data class TokenUsage(
    val planningInput: Int = 0,
    val planningOutput: Int = 0,
    val chatInput: Int = 0,
    val chatOutput: Int = 0
) {
    val totalInput: Int get() = planningInput + chatInput
    val totalOutput: Int get() = planningOutput + chatOutput
    val total: Int get() = totalInput + totalOutput

    fun addPlanning(input: Int, output: Int): TokenUsage =
        copy(planningInput = planningInput + input, planningOutput = planningOutput + output)

    fun addChat(input: Int, output: Int): TokenUsage =
        copy(chatInput = chatInput + input, chatOutput = chatOutput + output)

    fun isEmpty(): Boolean = total == 0

    fun formatDisplay(
        inputCostPerMillion: Double = 3.0,
        outputCostPerMillion: Double = 15.0
    ): String {
        if (isEmpty()) return ""
        val parts = mutableListOf<String>()
        val planTokens = planningInput + planningOutput
        val chatTokens = chatInput + chatOutput
        if (planTokens > 0)
            parts += "Plan: in ${formatTok(planningInput)} / out ${formatTok(planningOutput)}"
        if (chatTokens > 0)
            parts += "Chat: in ${formatTok(chatInput)} / out ${formatTok(chatOutput)}"
        val cost = totalInput / 1_000_000.0 * inputCostPerMillion +
                totalOutput / 1_000_000.0 * outputCostPerMillion
        parts += "~\$${String.format("%.3f", cost)}"
        return parts.joinToString("  |  ")
    }

    private fun formatTok(n: Int): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
        n >= 1_000 -> "${n / 1_000}k"
        else -> n.toString()
    }

    companion object {
        val EMPTY = TokenUsage()
    }
}
```

### 2.Добавить вспомогательный метод в старый `ChatSession`

        В `ChatHistoryService.kt` в класс `ChatSession` добавить метод конвертации — он понадобится на следующих шагах :

```kotlin
fun toTokenUsage(): TokenUsage = TokenUsage(
    planningInput = planningInputTokens,
    planningOutput = planningOutputTokens,
    chatInput = chatInputTokens,
    chatOutput = chatOutputTokens
)
```

Старые поля и методы (`addPlanningTokens`, `addChatTokens`, `formatTokenDisplay`) пока не трогай.

## Аналогичные классы в проекте

        Посмотри как устроены другие domain value objects:
-`maxvibes-domain/.../model/code/IdeError.kt` — data class без зависимостей
-`maxvibes-domain/.../model/context/ProjectContext.kt` — data class с вычисляемыми свойствами

## Что НЕ нужно делать

        -Не удалять старые поля токенов из `ChatSession`
-Не менять `TokenUsageAccumulator` в plugin
-Не переключать UI на новый класс — это будет на шаге 7 - 8

## Проверка после реализации

### Компиляция
```
./gradlew :maxvibes - domain:compileKotlin
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Unit тесты (написать в `maxvibes-domain`)

Создать файл : `maxvibes-domain/src/test/kotlin/com/maxvibes/domain/model/chat/TokenUsageTest.kt`

```kotlin
class TokenUsageTest {

    @Test
    fun `empty TokenUsage has zero totals`() {
        val usage = TokenUsage()
        assertEquals(0, usage.total)
        assertEquals(0, usage.totalInput)
        assertEquals(0, usage.totalOutput)
        assertTrue(usage.isEmpty())
    }

    @Test
    fun `addPlanning accumulates correctly`() {
        val usage = TokenUsage().addPlanning(100, 50)
        assertEquals(100, usage.planningInput)
        assertEquals(50, usage.planningOutput)
        assertEquals(150, usage.total)
        assertFalse(usage.isEmpty())
    }

    @Test
    fun `addChat accumulates correctly`() {
        val usage = TokenUsage().addChat(200, 80)
        assertEquals(200, usage.chatInput)
        assertEquals(80, usage.chatOutput)
        assertEquals(280, usage.total)
    }

    @Test
    fun `multiple additions accumulate`() {
        val usage = TokenUsage()
            .addPlanning(100, 50)
            .addChat(200, 80)
            .addChat(300, 120)
        assertEquals(100, usage.planningInput)
        assertEquals(50, usage.planningOutput)
        assertEquals(500, usage.chatInput)
        assertEquals(200, usage.chatOutput)
        assertEquals(850, usage.total)
    }

    @Test
    fun `totalInput sums planning and chat input`() {
        val usage = TokenUsage(planningInput = 100, chatInput = 200)
        assertEquals(300, usage.totalInput)
    }

    @Test
    fun `totalOutput sums planning and chat output`() {
        val usage = TokenUsage(planningOutput = 50, chatOutput = 80)
        assertEquals(130, usage.totalOutput)
    }

    @Test
    fun `formatDisplay returns empty string for zero usage`() {
        assertEquals("", TokenUsage.EMPTY.formatDisplay())
    }

    @Test
    fun `formatDisplay shows planning section when planning tokens present`() {
        val usage = TokenUsage(planningInput = 1000, planningOutput = 500)
        val display = usage.formatDisplay()
        assertTrue(display.contains("Plan:"))
        assertTrue(display.contains("1k"))
    }

    @Test
    fun `formatDisplay shows chat section when chat tokens present`() {
        val usage = TokenUsage(chatInput = 2000, chatOutput = 800)
        val display = usage.formatDisplay()
        assertTrue(display.contains("Chat:"))
        assertFalse(display.contains("Plan:"))
    }

    @Test
    fun `formatDisplay shows both sections when both present`() {
        val usage = TokenUsage(planningInput = 1000, planningOutput = 500, chatInput = 2000, chatOutput = 800)
        val display = usage.formatDisplay()
        assertTrue(display.contains("Plan:"))
        assertTrue(display.contains("Chat:"))
        assertTrue(display.contains("\$"))
    }

    @Test
    fun `formatDisplay calculates cost correctly`() {
        // 1M input tokens at $3/M = $3.000
        val usage = TokenUsage(chatInput = 1_000_000)
        val display = usage.formatDisplay(inputCostPerMillion = 3.0, outputCostPerMillion = 15.0)
        assertTrue(display.contains("\$3.000"))
    }

    @Test
    fun `TokenUsage is immutable - addPlanning returns new instance`() {
        val original = TokenUsage(planningInput = 100)
        val updated = original.addPlanning(50, 25)
        assertEquals(100, original.planningInput)  // original unchanged
        assertEquals(150, updated.planningInput)
    }

    @Test
    fun `EMPTY companion is zero`() {
        assertEquals(TokenUsage(), TokenUsage.EMPTY)
        assertTrue(TokenUsage.EMPTY.isEmpty())
    }
}
```

### Ручное тестирование
        1.Запустить плагин
        2.Отправить несколько сообщений
3.Убедиться что счётчик токенов в UI продолжает работать (он пока использует старый код)

## Коммит

```
refactor: add TokenUsage domain model with unit tests
```
