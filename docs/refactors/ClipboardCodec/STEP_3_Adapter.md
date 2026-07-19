# Step 3: Refactor ClipboardAdapter to delegate to codec

## Цель шага

        Переключить `ClipboardAdapter` на `JsonClipboardProtocolCodec` . Убрать из адаптера логику сериализации / десериализации — оставить только работу с системным буфером обмена .

## Предусловие

Шаги 1 и 2 завершены : `ClipboardProtocolCodec` и `JsonClipboardProtocolCodec` существуют и протестированы.

## Контекст: текущий ClipboardAdapter

**Путь * *: `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/clipboard/ClipboardAdapter.kt`

Сейчас содержит :
-`copyRequestToClipboard()` — сериализует + копирует в буфер
-`parseResponse()` — ищет JSON +десериализует
-`serializeRequest()` — **250 + строк логики, переезжает в кодек * *
-`parseUnifiedResponse()` — **переезжает в кодек * *
-`parseModification()` — **переезжает в кодек * *
-`findEmbeddedJson()` — **переезжает в кодек * *
-`extractSurroundingText()` — **переезжает в кодек * *

## Что делаем

### Новый ClipboardAdapter (полная замена файла)

```kotlin
package com.maxvibes.plugin.clipboard

import com . maxvibes . application . port . output . ClipboardPort
        import com . maxvibes . application . port . output . ClipboardProtocolCodec
        import com . maxvibes . domain . model . interaction . ClipboardRequest
        import com . maxvibes . domain . model . interaction . ClipboardResponse
        import com . maxvibes . plugin . service . MaxVibesLogger
        import java . awt . Toolkit
        import java . awt . datatransfer . StringSelection

/**
 * Реализация [ClipboardPort] для IntelliJ plugin.
 *
 * Тонкая обёртка: делегирует сериализацию/десериализацию [codec],
 * сам отвечает только за работу с системным буфером обмена (AWT Toolkit).
 *
 * Логика протокола — в [JsonClipboardProtocolCodec], тестируется отдельно.
 */
class ClipboardAdapter(
    private val codec: ClipboardProtocolCodec = JsonClipboardProtocolCodec()
) : ClipboardPort {

    override fun copyRequestToClipboard(request: ClipboardRequest): Boolean {
        return try {
            val jsonText = codec.encode(request)
            val selection = StringSelection(jsonText)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            MaxVibesLogger.debug(
                "Clipboard", "copied request", mapOf(
                    "task" to request.task.take(60),
                    "freshFiles" to request.freshFiles.size,
                    "planOnly" to request.planOnly
                )
            )
            true
        } catch (e: Exception) {
            MaxVibesLogger.error("Clipboard", "copyRequestToClipboard failed", e)
            false
        }
    }

    override fun parseResponse(rawText: String): ClipboardResponse? {
        val result = codec.decode(rawText)
        if (result != null) {
            MaxVibesLogger.debug(
                "Clipboard", "parsed response", mapOf(
                    "msg" to result.message.take(60),
                    "files" to result.requestedFiles.size,
                    "mods" to result.modifications.size
                )
            )
        } else {
            MaxVibesLogger.warn(
                "Clipboard", "failed to parse response",
                data = mapOf("preview" to rawText.take(120))
            )
        }
        return result
    }
}
```

## Проверка

### Компиляция
```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Smoke test (ручной)
1.Запустить плагин (`./gradlew runIde`)
2.Открыть MaxVibes Tool Window
        3.Выбрать режим * * Clipboard * *
4.Отправить любое сообщение
5.Убедиться что JSON скопировался в буфер (вставить в текстовый редактор)
6.Вставить валидный JSON - ответ обратно
        7.Убедиться что ответ распарсился : сообщение отображается, файлы запрашиваются

### Что проверяем в JSON (буфер)
Открыть любой текстовый редактор, вставить из буфера:
-Поле `_protocol` присутствует
-Поле `task` содержит твой запрос
-Поле `fileTree` заполнено
-Поля `planOnly` нет, если не включён plan -only режим

## Что убрано из ClipboardAdapter

        -`serializeRequest()` — **удалён * *, живёт в `JsonClipboardProtocolCodec`
-`parseUnifiedResponse()` — **удалён * *
-`parseModification()` — **удалён * *
-`findEmbeddedJson()` — **удалён * *
-`extractSurroundingText()` — **удалён * *
-`import kotlinx.serialization.json.*` — **удалён * *(теперь в кодеке)
-`private val json = Json { ... }` — **удалён * *
-`private val lenientJson = Json { ... }` — **удалён * *

## Возможные проблемы

**Если `ClipboardAdapter` используется в других местах кроме `ClipboardPort` * * — проверить через Find Usages.По архитектуре он должен быть только через порт .

**Если `MaxVibesService` создаёт `ClipboardAdapter` напрямую * * — убедиться что конструктор `ClipboardAdapter()` без аргументов работает(
    codec имеет default value
).

## Commit message

```
refactor: delegate ClipboardAdapter serialization to JsonClipboardProtocolCodec
```

## Следующий шаг

→ [STEP_4_Prompts.md](STEP_4_Prompts.md) — убрать дублирование промптов
