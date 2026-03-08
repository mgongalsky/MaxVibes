# Шаг 1: Перенести `MessageRole` в domain

## Контекст

Проект MaxVibes — IntelliJ IDEA плагин с Clean Architecture(6 модулей).Сейчас `MessageRole` живёт в `maxvibes-plugin` вместе с IntelliJ -специфичным кодом . Его нужно перенести в `maxvibes-domain` — модуль без каких -либо фреймворк -зависимостей.Это первый шаг большой миграции чат -модели в domain layer . Шаг намеренно маленький и безопасный .

## Что нужно сделать

### 1.Создать новый файл в domain

**Путь:** `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/MessageRole.kt`

```kotlin
package com.maxvibes.domain.model.chat

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}
```

Никаких аннотаций, никаких зависимостей . Только чистый Kotlin .

### 2.Обновить `ChatHistoryService.kt`

**Путь:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/chat/ChatHistoryService.kt`

-Удалить объявление `enum class MessageRole { USER, ASSISTANT, SYSTEM }` из этого файла
        -Добавить импорт : `import com.maxvibes.domain.model.chat.MessageRole`
        -Больше ничего не менять

### 3.Проверить другие файлы

Поищи `MessageRole` по всему проекту(Ctrl + Shift + F в IntelliJ).Везде где он используется через полный путь или требует явного импорта из plugin — обнови импорт на `com.maxvibes.domain.model.chat.MessageRole` .

Вероятные места :
-`ConversationPanel.kt`
-`ChatMessageController.kt`
-`ChatPanel.kt`

## Зависимости модулей

        `maxvibes-plugin` уже зависит от `maxvibes-domain`(через `build.gradle.kts`).Новый импорт будет работать без изменений в gradle .

## Аналогичный пример в проекте

        Посмотри как устроен `InteractionMode.kt` — это аналогичный enum уже живущий в domain :

```
maxvibes - domain / src / main / kotlin / com / maxvibes / domain / model / interaction / InteractionMode.kt
```

Твой `MessageRole.kt` должен выглядеть точно так же по структуре.

## Что НЕ нужно делать

        -Не трогать XML - аннотации(`@Attribute`, `@Tag`) — они останутся на других классах
-Не переносить `ChatMessage` или `ChatSession` — это следующие шаги
-Не менять логику — только перемещение

## Проверка после реализации

### Компиляция
```
./gradlew :maxvibes - domain:compileKotlin
    ./ gradlew : maxvibes -plugin:compileKotlin
```
Оба должны завершиться без ошибок.

### Ручное тестирование
        1.Запустить плагин (`./gradlew runIde`)
2.Открыть чат MaxVibes
3.Отправить сообщение — убедиться что оно появляется с правильной ролью (USER)
4.Получить ответ от LLM — убедиться что роль ASSISTANT отображается корректно
5.Открыть существующую сессию из истории — сообщения загружаются правильно

### Автотесты
Автотесты для этого шага не требуются — изменение чисто механическое (перемещение enum).

## Коммит

```
refactor: move MessageRole to domain layer
```
