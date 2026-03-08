# Step 6: Final Cleanup

## Контекст задачи

        Это финальный шаг рефакторинга ChatPanel.Шаги 1–5 выполнены :
-`ConversationRenderer` — создан
-`InteractionModeManager` — создан
-`ChatPanelState` + `render()` — введены
-Attachment Management — перенесён в Controller
        -Session Operations — перенесены в Controller

        `ChatPanel` уже стал тонким View.На этом шаге убираем технический долг, накопленный за время всего рефакторинга.

---

## Задача

### 6 a . Централизовать конвертацию `ChatMessage → ChatMessageDTO`

**Проблема:** функция `toChatMessageDTO()` дублируется в трёх местах :
-`ChatPanel.kt`
-`ChatMessageController.kt`
-`ChatHistoryService.kt`

**Решение:** создать один файл с extension - функциями.

**Создать файл : * * `maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/dto/ChatMessageMapper.kt`

```kotlin
package com.maxvibes.adapter.llm.dto

import com . maxvibes . application . port . output . ChatMessageDTO
        import com . maxvibes . application . port . output . ChatRole
        import com . maxvibes . domain . model . chat . ChatMessage
        import com . maxvibes . domain . model . chat . MessageRole

/**
 * Маппинг между доменным ChatMessage и ChatMessageDTO для LLM адаптера.
 * Единственное место, где живёт эта конвертация.
 */
fun ChatMessage.toChatMessageDTO(): ChatMessageDTO = ChatMessageDTO(
    role = when (role) {
        MessageRole.USER -> ChatRole.USER
        MessageRole.ASSISTANT -> ChatRole.ASSISTANT
        MessageRole.SYSTEM -> ChatRole.SYSTEM
    },
    content = content
)

fun List<ChatMessage>.toChatMessageDTOs(): List<ChatMessageDTO> =
    map { it.toChatMessageDTO() }
```

> Адаптируй `MessageRole` и `ChatRole` значения под реальный enum в проекте .

**Удалить * * дубликаты `toChatMessageDTO()` из :
-`ChatPanel.kt`
-`ChatMessageController.kt`
-`ChatHistoryService.kt`

**Добавить импорт * * в файлы, которые используют функцию:
```kotlin
import com . maxvibes . adapter . llm . dto . toChatMessageDTO
        import com . maxvibes . adapter . llm . dto . toChatMessageDTOs
```

### 6 b . Удалить `src/` в корне проекта

        В корне проекта есть папка `src/main/kotlin/` — артефакт предыдущего рефакторинга (миграция chat entities в domain layer).Она содержит :
-`STEP_1_MessageRole.md` ... `STEP_9_Cleanup.md` — документация шагов, уже не нужна
-`IntellijIdeErrorsAdapter.kt` — должен находиться в `maxvibes-adapter-psi`, не в корне
-Пустые пакеты `com/maxvibes/maxvibes/`

**Проверить * * что `IntellijIdeErrorsAdapter.kt` из корня уже есть в правильном месте :
`maxvibes-adapter-psi/src/main/kotlin/com/maxvibes/adapter/psi/context/IntellijIdeErrorsAdapter.kt`

Если есть — файл в корне является дубликатом и его можно удалить .

**Удалить папку * * `src/` в корне проекта целиком.

**Проверить `settings.gradle.kts` * * — убедиться что корневой `src/` не включён как источник кода :
```bash
grep - r "src/main" settings . gradle . kts build.gradle.kts
```

Если там нет упоминания корневого `src/` — удаление безопасно.

### 6 c . Проверить и удалить `TokenUsageAccumulator`(если unused)

Посмотреть файл `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/TokenUsageAccumulator.kt`.Если он нигде не используется(
    токены теперь хранятся в `ChatSession` напрямую
) — удалить файл .

Проверка:
```bash
grep - r "TokenUsageAccumulator" maxvibes -plugin / src /
```

Если вывод пустой — файл не используется, можно удалять .

### 6 d . Финальная проверка ChatPanel

        Проверить что в `ChatPanel.kt` больше нет :
-Прямых вызовов `chatTreeService`
-`executeOnPooledThread`
-`runBlocking`
-Дублированной `toChatMessageDTO()`
        -Методов с business - логикой(только UI +вызовы controller)

Подсчитать строки :
```bash
wc - l maxvibes -plugin / src / main / kotlin / com / maxvibes / plugin / ui / ChatPanel.kt
```

Целевой результат : * *≤ 250 строк * * .

### 6e.Обновить документацию

**Обновить * * `docs/CURRENT_STATUS.md` — добавить секцию о завершённом рефакторинге ChatPanel.

**Обновить * * `docs/ARCHITECTURE_RESEARCH.md` — в разделе ChatPanel обновить статус с "Требует внимания" на "Хорошо".

**Создать или обновить * * `docs/refactoring/CHATPANEL_REFACTORING_RESULTS.md` с итогами :
-Сколько строк стало в ChatPanel
-Какие классы созданы
-Сколько тестов добавлено

### 6f.Компиляция и тесты

```bash
    ./ gradlew build
```

Должно пройти без ошибок .

---

## Полный smoke test(ручное тестирование)

Это финальная проверка всего рефакторинга.Каждый пункт должен работать :

**Базовые сценарии : * *
-[] Открыть плагин — загружается история сессий
-[] Отправить сообщение в API режиме — получить ответ
-[] Отправить сообщение в Clipboard режиме — текст копируется в буфер
-[] Отправить сообщение в CheapAPI режиме (если настроен)

**Управление сессиями : * *
-[] Создать новую сессию — "New Chat"
-[] Переключиться между сессиями в дереве
        -[] Переименовать сессию
-[] Удалить сессию
-[] Создать ветку(branch) от сессии

**Attachments:**
-[] Прикрепить trace из clipboard
-[] Загрузить ошибки IDE
        -[] Отправить сообщение с прикреплёнными данными — они включены в запрос
-[] После отправки attachments очищаются

**Persistence:**
-[] Закрыть и открыть IDE — история сессий сохранилась
-[] Токены сессии сохранились и отображаются

**Режимы:**
-[] Переключить режим — индикатор меняется
        -[] Изменить режим в Settings — при открытии плагина применяется новый режим

        ---

## Автоматические тесты

### Тест маппера

**Создать файл : * * `maxvibes-adapter-llm/src/test/kotlin/com/maxvibes/adapter/llm/dto/ChatMessageMapperTest.kt`

```kotlin
package com.maxvibes.adapter.llm.dto

import com . maxvibes . application . port . output . ChatRole
        import com . maxvibes . domain . model . chat . ChatMessage
        import com . maxvibes . domain . model . chat . MessageRole
        import org . junit . jupiter . api . Test
        import org . junit . jupiter . api . Assertions . *

class ChatMessageMapperTest {

    @Test
    fun `toChatMessageDTO maps USER role correctly`() {
        val message = ChatMessage(role = MessageRole.USER, content = "Hello")
        val dto = message.toChatMessageDTO()
        assertEquals(ChatRole.USER, dto.role)
        assertEquals("Hello", dto.content)
    }

    @Test
    fun `toChatMessageDTO maps ASSISTANT role correctly`() {
        val message = ChatMessage(role = MessageRole.ASSISTANT, content = "Hi")
        val dto = message.toChatMessageDTO()
        assertEquals(ChatRole.ASSISTANT, dto.role)
    }

    @Test
    fun `toChatMessageDTOs converts list correctly`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Q"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "A")
        )
        val dtos = messages.toChatMessageDTOs()
        assertEquals(2, dtos.size)
        assertEquals(ChatRole.USER, dtos[0].role)
        assertEquals(ChatRole.ASSISTANT, dtos[1].role)
    }

    @Test
    fun `toChatMessageDTOs returns empty list for empty input`() {
        val dtos = emptyList<ChatMessage>().toChatMessageDTOs()
        assertTrue(dtos.isEmpty())
    }

    @Test
    fun `toChatMessageDTO preserves content exactly`() {
        val content = "Multi\nline\ncontent with special chars: <>\"'"
        val message = ChatMessage(role = MessageRole.USER, content = content)
        val dto = message.toChatMessageDTO()
        assertEquals(content, dto.content)
    }
}
```

### Запустить все тесты рефакторинга

```bash
    ./ gradlew : maxvibes -plugin:test-- tests "com.maxvibes.plugin.ui.*"
    ./ gradlew : maxvibes -adapter - llm:test-- tests "com.maxvibes.adapter.llm.dto.*"
    ./ gradlew test
```

Все тесты должны проходить .

---

## Коммит

```
refactor: ChatPanel cleanup — centralize DTO mapping, remove stale files
```

---

## Итог всего рефакторинга

После завершения шагов 1–6 структура `plugin/ui/` выглядит так:

```
plugin / ui /
├── ChatPanel.kt              # View ~200 строк : UI +лямбды - роутеры + render()
├── ChatMessageController.kt  # Presenter: сообщения + attachments + сессии
├── ConversationRenderer.kt   # Фильтрация и форматирование сообщений
├── InteractionModeManager.kt # State machine режимов
├── ChatPanelState.kt         # Data class состояния
├── SessionTreePanel.kt       # без изменений
├── ConversationPanel.kt      # без изменений
└── ...
```

**ChatPanel соответствует SRP:** одна ответственность — отображать состояние и маршрутизировать события .
