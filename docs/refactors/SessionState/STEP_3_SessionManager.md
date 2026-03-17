# STEP 3: Application — ClipboardSessionManager(конечный автомат)

## Контекст

Это центральный шаг рефакторинга . Создаём изолированный сервис -менеджер, который владеет логикой переходов clipboard - состояния.Он не знает ничего об IntelliJ SDK, тестируется через Gradle без IDE.На этом шаге мы только создаём новый компонент, не подключая его к `ClipboardInteractionService` — это следующий шаг.Риск минимальный : существующее поведение не изменяется.См.общий план : `docs/refactors/SessionState/PLAN.md`

## Что создаём

### 1.`ClipboardEvent.kt`

**Путь:** `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardEvent.kt`

Sealed class с четырьмя событиями :

```
StartSession    — пользователь отправил первое сообщение в новой clipboard - сессии
JsonCopied      — JSON сгенерирован и скопирован в буфер обмена
ResponsePasted  — ответ LLM вставлен и начал обрабатываться
        Reset           — сессия сброшена явно(новый чат, удаление, переключение)
```

KDoc на каждом значении обязателен.

### 2.`ClipboardSessionManager.kt`

**Путь:** `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardSessionManager.kt`

**Зависимости конструктора : * *
-`repository: ChatSessionRepository` — для чтения и сохранения сессий
-`logger: LoggerPort? = null` — опциональный, для тестируемости без логгера

**Публичный API : * *

```kotlin
/** Возвращает текущий статус для сессии. IDLE если сессия не найдена. */
fun statusFor(sessionId: String): ClipboardSessionStatus

/**
 * Запрашивает переход состояния для сессии.
 * @return true если переход валиден и применён, false если переход невалиден
 */
fun transition(sessionId: String, event: ClipboardEvent): Boolean
```

**Матрица переходов : * *

| Event \ Status | IDLE | SESSION_ACTIVE | AWAITING_PASTE |
|---|-- - |-- - |-- - |
| StartSession | → SESSION_ACTIVE ✓ | ⚠ warn → false | ⚠ warn → false |
| JsonCopied | ⚠ warn → false | → AWAITING_PASTE ✓ | → AWAITING_PASTE ✓ (ре - отправка) |
| ResponsePasted | ⚠ warn → false | ⚠ warn → false | → SESSION_ACTIVE ✓ |
| Reset | → IDLE ✓ (no - op) | → IDLE ✓ | → IDLE ✓ |

**Внутренняя логика `transition()`:**
1.Получить сессию из репозитория (`repository.getSessionById(sessionId)`)
2.Если сессия не найдена — залогировать warn, вернуть `false`
        3.Определить новый статус по матрице
4.Если переход невалиден — залогировать warn с текущим статусом и событием, вернуть `false`
        5.Если переход no - op(Reset из IDLE) — залогировать debug, вернуть `true`
        6.Сохранить: `repository.saveSession(session.withClipboardStatus(newStatus))`
7.Залогировать info : `sessionId, from, event, to`
        8.Вернуть `true`

**Важно:** невалидный переход никогда не бросает исключение — система должна оставаться рабочей даже при неожиданных вызовах.

## Тесты

**Путь:** `maxvibes-application/src/test/kotlin/com/maxvibes/application/service/ClipboardSessionManagerTest.kt`

Использовать `InMemoryChatSessionRepository` (если существует в проекте) или создать простую in - memory реализацию прямо в тестовом файле .

### Валидные переходы (должны вернуть true и изменить статус):
1.`IDLE + StartSession → SESSION_ACTIVE`
2.`SESSION_ACTIVE + JsonCopied → AWAITING_PASTE`
3.`AWAITING_PASTE + JsonCopied → AWAITING_PASTE`(ре - отправка разрешена)
4.`AWAITING_PASTE + ResponsePasted → SESSION_ACTIVE`
5.`SESSION_ACTIVE + Reset → IDLE`
6.`AWAITING_PASTE + Reset → IDLE`
7.`IDLE + Reset → IDLE`(no - op, но возвращает true)

### Невалидные переходы (должны вернуть false, статус не изменился):
8.`IDLE + JsonCopied → false, статус остался IDLE`
9.`IDLE + ResponsePasted → false, статус остался IDLE`
10.`SESSION_ACTIVE + StartSession → false, статус остался SESSION_ACTIVE`
11.`SESSION_ACTIVE + ResponsePasted → false, статус остался SESSION_ACTIVE`
12.`AWAITING_PASTE + StartSession → false, статус остался AWAITING_PASTE`

### Прочее:
13.`statusFor()` для несуществующей сессии → `IDLE`
        14.`transition()` для несуществующей сессии → `false`
        15.После `Reset` из любого состояния статус гарантированно `IDLE` (параметризованный тест)

### Паттерн тестов :
```kotlin
class ClipboardSessionManagerTest {
    private lateinit var repository: InMemoryChatSessionRepository
    private lateinit var manager: ClipboardSessionManager

    @BeforeEach
    fun setUp() {
        repository = InMemoryChatSessionRepository()
        manager = ClipboardSessionManager(repository, logger = null)
    }

    private fun sessionWithStatus(status: ClipboardSessionStatus): ChatSession {
        val session = ChatSession()
        return session.withClipboardStatus(status).also { repository.saveSession(it) }
    }
}
```

Использовать `runBlocking` если методы репозитория suspend (проверить по контракту).

## Проверка

```bash
    ./ gradlew : maxvibes -application:test
```

Все новые тесты зелёные . Существующие тесты не сломаны.

## Коммит

```
feat(application): add ClipboardSessionManager state machine with tests
```
