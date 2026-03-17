# STEP 5: Plugin — DI, подключение ClipboardSessionManager в MaxVibesService

## Контекст

На этом шаге подключаем `ClipboardSessionManager` в граф зависимостей плагина.После этого шага вся цепочка работает : менеджер создаётся, передаётся в сервис, переходы состояния применяются и персистируются.Шаг небольшой, но важный — здесь мы убираем временный nullable -дефолт из STEP 4 и делаем зависимость обязательной.См.общий план : `docs/refactors/SessionState/PLAN.md`

## Что делаем

### 1.Создать `clipboardSessionManager` в `MaxVibesService`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/MaxVibesService.kt`

Добавить lazy -свойство после `chatTreeService`:
```kotlin
val clipboardSessionManager: ClipboardSessionManager by lazy {
    ClipboardSessionManager(
        repository = chatSessionRepository,
        logger = loggerPort
    )
}
```

### 2.Передать менеджер в `clipboardService`

        Обновить существующую lazy - инициализацию `clipboardService` :
```kotlin
val clipboardService: ClipboardInteractionService by lazy {
    ClipboardInteractionService(
        contextProvider = projectContextProvider,
        clipboardPort = ClipboardAdapter(),
        codeRepository = codeRepository,
        notificationPort = notificationPort,
        promptPort = promptPort,
        logger = MaxVibesLogger,
        sessionManager = clipboardSessionManager   // ← добавить
    )
}
```

### 3.Убрать nullable -дефолт из `ClipboardInteractionService`

**Путь:** `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt`

Изменить параметр конструктора:
```kotlin
// Было:
private val sessionManager: ClipboardSessionManager? = null

// Стало:
private val sessionManager: ClipboardSessionManager
```

Убрать везде safe - call `?.` на `sessionManager` — теперь он гарантированно не null.В методе `currentStatus()` убрать elvis - оператор:
```kotlin
private fun currentStatus(sessionId: String): ClipboardSessionStatus =
    sessionManager.statusFor(sessionId)
```

## Проверка

```bash
    ./ gradlew : maxvibes -application:compileKotlin
    ./ gradlew : maxvibes -plugin:compileKotlin
    ./ gradlew : maxvibes -application:test
```

### Ручная проверка в IDE :
1.Запустить плагин
        2.Переключиться в Clipboard - режим
3.Отправить сообщение → JSON копируется в буфер
4.Проверить в XML что `clipboardStatus="AWAITING_PASTE"` для текущей сессии
        5.Вставить ответ → проверить что статус стал `SESSION_ACTIVE`
        6.Создать новый чат → `clipboardStatus` нового чата `IDLE`, предыдущий не изменился

## Коммит

```
feat(plugin): wire ClipboardSessionManager into MaxVibesService
```
