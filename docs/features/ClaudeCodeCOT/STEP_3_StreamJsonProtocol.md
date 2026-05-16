# STEP 3 — StreamJsonProtocol: add isRateLimitEvent helper

## Цель

Добавить узкий хелпер для распознавания `rate_limit_event` событий.Остальное
(`extractSessionId`, `extractAssistantText`, `isTurnEnd`) уже есть — переиспользуем
        как есть .

## Файл

**Редактировать:**
`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/claudecode/StreamJsonProtocol.kt`

## Изменение

Добавить в `internal object StreamJsonProtocol` функцию `extractRateLimitInfo`:

```kotlin
/**
 * Returns a short human-readable summary if the line is a `rate_limit_event`,
 * null otherwise.
 *
 * The exact shape of rate_limit_event payloads is not formally documented —
 * we conservatively look for known fields (`message`, `reset_seconds`, `tier`)
 * and fall back to a generic "rate limit notice" string. UI uses this only for
 * informational display in the live bubble; missing detail is acceptable.
 */
fun extractRateLimitInfo(line: String): String? {
    val obj = parseLine(line) ?: return null
    if (obj["type"]?.jsonPrimitive?.contentOrNull != "rate_limit_event") return null

    val message = obj["message"]?.jsonPrimitive?.contentOrNull
    if (!message.isNullOrBlank()) return message

    val resetSeconds = obj["reset_seconds"]?.jsonPrimitive?.contentOrNull
    if (!resetSeconds.isNullOrBlank()) return "rate limit, resets in ${resetSeconds}s"

    return "rate limit notice"
}
```

Импорты в файле уже включают `jsonPrimitive`, `contentOrNull` — добавлять ничего
        не нужно .

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

## Backward compatibility

        Чисто аддитивное изменение — новая функция в существующем `object`.

## Commit

```
feat: add isRateLimitEvent helper to StreamJsonProtocol
```
