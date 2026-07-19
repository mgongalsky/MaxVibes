# Step 6 — ClipboardSessionManager: транзишены под AWAITING_APPROVE

## Цель

Расширить существующий `ClipboardSessionManager` так, чтобы он умел делать переходы в новый статус `AWAITING_APPROVE` и обратно.Без него ни Service из Step 5, ни UI из Step 8 не смогут корректно вести диалог Claude Code .

## Затрагиваемые файлы

| Файл | Действие |
|------|----------|
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardSessionManager.kt` | MODIFY |
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardEvent.kt` | MODIFY(добавить event) |

Зависит от Step 1(новый литерал `AWAITING_APPROVE`).

## Контекст

Текущая transition matrix `ClipboardSessionManager` :

```
Event \ Status   | IDLE              | SESSION_ACTIVE     | AWAITING_PASTE
-----------------+-------------------+--------------------+-------------------
StartSession     | → SESSION_ACTIVE  | warn → false       | warn → false
JsonCopied       | warn → false      | → AWAITING_PASTE   | → AWAITING_PASTE
ResponsePasted   | warn → false      | warn → false       | → SESSION_ACTIVE
Reset            | no - op → true      | → IDLE             | → IDLE
```

Для Claude Code режима добавляем два события и ещё один статус:

-`ResponseReceived` — Claude Code прислал ответ; если в ответе есть `requestedViews` — переходим в `AWAITING_APPROVE`, иначе — `SESSION_ACTIVE`.
-`Approved` — юзер нажал Approve; переходим из `AWAITING_APPROVE` обратно в `SESSION_ACTIVE` (после чего сервис сам вызовет следующий send).

**Важно:** при попытке вызывать clipboard -события(
    `JsonCopied`,
    `ResponsePasted`
) в статусе `AWAITING_APPROVE` — warn → false, как при любом несовместимом transition.

## Расширенная transition matrix

```
Event \ Status     | IDLE             | SESSION_ACTIVE     | AWAITING_PASTE     | AWAITING_APPROVE
-------------------+------------------+--------------------+--------------------+------------------
StartSession       | → SESSION_ACTIVE | warn → false       | warn → false       | warn → false
JsonCopied         | warn → false     | → AWAITING_PASTE   | → AWAITING_PASTE   | warn → false
ResponsePasted     | warn → false     | warn → false       | → SESSION_ACTIVE   | warn → false
ResponseReceived   | warn → false     | → SESSION_ACTIVE / | warn → false       | → SESSION_ACTIVE /
        (with views =...) |                  |   AWAITING_APPROVE |                    |   AWAITING_APPROVE
Approved           | warn → false     | warn → false       | warn → false       | → SESSION_ACTIVE
Reset              | no - op → true     | → IDLE             | → IDLE             | → IDLE
```

`ResponseReceived` параметризован (`hasRequestedViews: Boolean`), поэтому транзишен зависит от его значения .

## Изменения

### 6.1 `ClipboardEvent.kt` — добавить события

Прочитать файл `FULL`.Добавить:

```kotlin
/**
 * Claude Code response was received and parsed.
 * If [hasRequestedViews] is true → status becomes AWAITING_APPROVE.
 * If false → status becomes SESSION_ACTIVE.
 */
data class ResponseReceived(val hasRequestedViews: Boolean) : ClipboardEvent()

/**
 * User pressed Approve in AWAITING_APPROVE state.
 * Status returns to SESSION_ACTIVE so the service can send the next request.
 */
object Approved : ClipboardEvent()
```

Если `ClipboardEvent` сейчас sealed class в `ClipboardSessionManager.kt` (а не отдельный файл) — добавить туда же.

### 6.2 `ClipboardSessionManager.kt` — обновить `resolveTransition`

Прочитать файл `FULL`.Найти метод `resolveTransition(current, event): ClipboardSessionStatus?`.Добавить ветки :

```kotlin
is ClipboardEvent.ResponseReceived ->
when (current) {
    ClipboardSessionStatus.SESSION_ACTIVE,
    ClipboardSessionStatus.AWAITING_APPROVE ->
        if (event.hasRequestedViews) ClipboardSessionStatus.AWAITING_APPROVE
        else ClipboardSessionStatus.SESSION_ACTIVE

    else -> null  // invalid: warn and return false
}

ClipboardEvent.Approved ->
when (current) {
    ClipboardSessionStatus.AWAITING_APPROVE -> ClipboardSessionStatus.SESSION_ACTIVE
    else -> null
}
```

И обновить existing ветки чтобы корректно обрабатывали `AWAITING_APPROVE` (везде где раньше был только `AWAITING_PASTE` — добавить ветку или явно warn → null):

```kotlin
is ClipboardEvent.StartSession ->
when (current) {
    ClipboardSessionStatus.IDLE -> ClipboardSessionStatus.SESSION_ACTIVE
    else -> null
}

ClipboardEvent.JsonCopied ->
when (current) {
    ClipboardSessionStatus.SESSION_ACTIVE,
    ClipboardSessionStatus.AWAITING_PASTE -> ClipboardSessionStatus.AWAITING_PASTE

    else -> null  // включая AWAITING_APPROVE
}

ClipboardEvent.ResponsePasted ->
when (current) {
    ClipboardSessionStatus.AWAITING_PASTE -> ClipboardSessionStatus.SESSION_ACTIVE
    else -> null
}

ClipboardEvent.Reset ->
when (current) {
    ClipboardSessionStatus.IDLE -> current  // no-op valid
    else -> ClipboardSessionStatus.IDLE
}
```

### 6.3 KDoc обновить

Дописать в class- level KDoc `ClipboardSessionManager` обновлённую матрицу из этого файла.Это важно — документация в коде должна точно отражать поведение .

## Что НЕ делать

-Не создавать `ClaudeCodeSessionManager` — переиспользуем существующий . Менеджер — protocol -agnostic state machine, ему всё равно, кто события шлёт.
-Не разделять enum `ClipboardSessionStatus` на два — `AWAITING_APPROVE` живёт в общем enum и он остаётся «clipboard - named», что соответствует нашему решению не переименовывать сейчас.
-Не трогать `ChatHistoryService`(persistence) — он сериализует enum по имени, новый литерал `AWAITING_APPROVE` будет работать автоматически .

## Тесты

`ClipboardSessionManagerTest` — добавить кейсы :

-`responseReceivedWithViews_inSessionActive_transitionsToAwaitingApprove`
-`responseReceivedWithoutViews_inSessionActive_staysInSessionActive`
-`responseReceivedWithViews_inAwaitingApprove_staysInAwaitingApprove`(повторная отправка с requestedViews)
-`approved_inAwaitingApprove_transitionsToSessionActive`
-`approved_inIdle_warnsAndReturnsFalse`
-`jsonCopied_inAwaitingApprove_warnsAndReturnsFalse`(clipboard - event несовместим с CC -state)
-`reset_inAwaitingApprove_transitionsToIdle`

## Acceptance criteria

        -[] `./gradlew :maxvibes-application:test` зелёный
-[] Все семь новых кейсов покрыты
        -[] Старые тесты не упали — старая транзишен -матрица сохранена
        -[] KDoc actualizirovan
