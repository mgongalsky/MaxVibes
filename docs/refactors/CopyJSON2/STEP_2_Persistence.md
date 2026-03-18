# Step 2: XML-сериализация requestedFiles

## Контекст

После Step 1 в `ChatMessage` появилось поле `requestedFiles: List<String>`.
Нужно обновить `ChatHistoryService` — чтение и запись. Старые XML без этого тега
читаются как `emptyList()` (backward compatible).

## Файл для изменения

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/chat/ChatHistoryService.kt`

> **Перед написанием кода** — прочитай файл и следуй уже используемому подходу
> (JDOM Element или другой). Ниже паттерн для JDOM.

### Запись (message → XML)

```kotlin
if (message.requestedFiles.isNotEmpty()) {
val filesElem = Element("requestedFiles")
message.requestedFiles.forEach { path ->
filesElem.addContent(Element("path").setText(path))
}
msgElem.addContent(filesElem)
}
```

### Чтение (XML → message)

```kotlin
val requestedFiles: List<String> = msgElem.getChild("requestedFiles")
?.getChildren("path")
?.map { it.text }
?: emptyList()

ChatMessage(
...,
requestedFiles = requestedFiles
)
```

## Проверка

```bash
./gradlew :maxvibes-plugin:compileKotlin
```

1. Запустить плагин
2. Сессия: Generate → ЛЛМ запросил файлы → вставить ответ
3. Перезапустить IDE
4. Убедиться что сессия загружается без ошибок
5. В XML сообщения ассистента должен быть тег `<requestedFiles>`

## Коммит

```
feat(persistence): serialize requestedFiles in ChatHistoryService XML
```
