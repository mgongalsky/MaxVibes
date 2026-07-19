# STEP 1: Domain — ClipboardSessionStatus + поле в ChatSession

## Контекст

Это первый шаг рефакторинга per - session clipboard state.Цель шага — ввести типизированный статус clipboard - диалога в доменную модель, не затрагивая никаких других слоёв.После этого шага компилируется весь проект, проходят все тесты, поведение системы не меняется .

См.общий план : `docs/refactors/SessionState/PLAN.md`

## Что делаем

### 1.Создать `ClipboardSessionStatus.kt`

**Путь:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/interaction/ClipboardSessionStatus.kt`

Enum с тремя значениями :

```
IDLE           — нет активного clipboard - диалога для этой сессии
        SESSION_ACTIVE — диалог открыт, ждём следующего сообщения пользователя
        AWAITING_PASTE — JSON скопирован в буфер, ждём вставки ответа LLM
```

Файл должен быть в пакете `com.maxvibes.domain.model.interaction` (рядом с `InteractionMode`, `ClipboardProtocol`).

Добавить KDoc -комментарий к каждому значению enum.

### 2.Добавить поле в `ChatSession`

**Путь:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/ChatSession.kt`

Добавить поле с дефолтным значением:
```kotlin
val clipboardStatus: ClipboardSessionStatus = ClipboardSessionStatus.IDLE
```

Поле должно быть последним в списке конструктора(после `updatedAt`), чтобы не сломать существующие вызовы `copy()` и конструкторов без именованных аргументов.Добавить helper -метод:
```kotlin
fun withClipboardStatus(status: ClipboardSessionStatus): ChatSession =
    copy(clipboardStatus = status, updatedAt = Instant.now().toEpochMilli())
```

### 3.Добавить импорт в ChatSession

        В `ChatSession.kt` нужен импорт нового enum :
```kotlin
import com . maxvibes . domain . model . interaction . ClipboardSessionStatus
```

## Что НЕ трогаем

-`ClipboardInteractionService` — без изменений
        -`ChatHistoryService` / `XmlChatSession` — без изменений (персистенция — следующий шаг)
-Любые UI -классы — без изменений
        -Любые application -сервисы — без изменений

## Тесты

Создать файл : `maxvibes-domain/src/test/kotlin/com/maxvibes/domain/model/chat/ChatSessionClipboardStatusTest.kt`

        Покрыть:
1.Дефолтный `clipboardStatus` у нового `ChatSession` равен `IDLE`
2.`withClipboardStatus(SESSION_ACTIVE)` возвращает новый объект с нужным статусом
3.`withClipboardStatus()` не мутирует оригинальный объект(иммутабельность)
4.`withClipboardStatus()` обновляет `updatedAt`(проверить что новое значение >= старого)
5.Все остальные поля(`id`, `title`, `messages`, `tokenUsage`) не изменяются при `withClipboardStatus()`
6.`copy()` без явного `clipboardStatus` сохраняет существующее значение

## Проверка

```bash
    ./ gradlew : maxvibes -domain:compileKotlin
    ./ gradlew : maxvibes -domain:test
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -adapter - llm:compileKotlin
```

Всё должно компилироваться.Существующие тесты не должны сломаться.

## Коммит

```
feat(domain): add ClipboardSessionStatus enum and field to ChatSession
```
