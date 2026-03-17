# STEP 8: Cleanup — удаление deprecated кода

## Контекст

Финальный шаг . Убираем весь временный код, заглушки и deprecated - методы, добавленные для плавного перехода в предыдущих шагах.См.общий план : `docs/refactors/SessionState/PLAN.md`

## Что удаляем

### 1.Deprecated методы в `ClipboardInteractionService`

        Удалить:
```kotlin
@Deprecated("Use status(sessionId) instead")
fun isWaitingForResponse(): Boolean = false

@Deprecated("Use status(sessionId) instead")
fun hasActiveSession(): Boolean = false
```

Проверить что нигде в кодовой базе не осталось вызовов этих методов(IDE должна подсветить).

### 2.Поле `isWaitingResponse` из `ChatPanelState` (если ещё не удалено в STEP 6)

Удалить поле :
```kotlin
val isWaitingResponse: Boolean = false
```

Найти все использования в `render()` и заменить на :
```kotlin
state.clipboardStatus == ClipboardSessionStatus.AWAITING_PASTE
```

### 3.Метод `ChatPanel.resetClipboard()` (если больше не используется)

Если после STEP 7 метод нигде не вызывается — удалить.Если используется — оставить с актуальной реализацией.

### 4.`getCurrentPhase()` в `ClipboardInteractionService`(если устарел)

Метод `getCurrentPhase()` использовался в `updateModeUI()` для отображения фазы (PLANNING / CHAT) в индикаторе.Проверить:
-Если фаза теперь берётся из `ChatSession.clipboardStatus` или других полей — удалить
-Если всё ещё нужен — оставить, но задокументировать

### 5.Проверить отсутствие прямых обращений к флагам сервиса из UI

Поиск по кодовой базе :
```
grep - r "isWaitingForResponse\|hasActiveSession\|waitingForPaste" maxvibes -plugin /
```

Должно быть ноль совпадений .

### 6.Обновить документацию

        Проверить что KDoc - комментарии актуальны :
-`ClipboardInteractionService` — обновить описание публичного API
        -`ClipboardSessionManager` — убедиться что матрица переходов задокументирована
-`ChatSession` — добавить описание поля `clipboardStatus`
        -`ChatPanelState` — описание поля `clipboardStatus`

## Финальная проверка

### Тесты:
```bash
    ./ gradlew : maxvibes -domain:test
    ./ gradlew : maxvibes -application:test
    ./ gradlew : maxvibes -adapter - llm:test
    ./ gradlew : maxvibes -shared:test
```

Все тесты зелёные.

### Компиляция:
```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Ручной тест :
Полный clipboard workflow(см.STEP 7) — проверить что ничего не сломалось после cleanup.

### Поиск dead code:
```bash
grep - r "isWaitingForResponse\|hasActiveSession\|waitingForPaste\|resetClipboard" maxvibes -plugin /
        grep - r "isWaitingForResponse\|hasActiveSession" maxvibes -application /
```

## Коммит

```
chore: cleanup deprecated clipboard state accessors and dead code
```
