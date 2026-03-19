# STEP 1 — Доменные модели

## Цель

Добавить в `maxvibes-domain` три новых типа :
-`CodeGranularity` — enum с вариантами частичной выдачи
-`CodeViewRequest` — запрос конкретного « вида » файла
        -`CodeView` — результат: готовый текст для вставки в промпт

## Модуль

`maxvibes-domain`

## Зависимости

Нет.Только Kotlin stdlib.Этот шаг не затрагивает ни один другой модуль .

## Файлы для создания

### `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/code/CodeGranularity.kt`

```kotlin
package com.maxvibes.domain.model.code

/**
 * Уровень детализации при запросе содержимого файла.
 * Используется для минимизации входящих токенов:
 * LLM запрашивает ровно столько контекста, сколько нужно для задачи.
 */
enum class CodeGranularity {

    /** Полный файл — текущее поведение по умолчанию. */
    FULL,

    /**
     * Только сигнатуры: все декларации верхнего уровня и члены классов
     * без тел функций. Полезно для понимания структуры файла.
     */
    SIGNATURES,

    /**
     * Outline класса: суперклассы, свойства (имя + тип),
     * сигнатуры методов. Компактнее SIGNATURES для больших классов.
     */
    OUTLINE,

    /**
     * Конкретный элемент по [com.maxvibes.domain.model.code.ElementPath].
     * Возвращает полный текст элемента включая тело.
     */
    ELEMENT
}
```

### `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/code/CodeViewRequest.kt`

```kotlin
package com.maxvibes.domain.model.code

/**
 * Запрос на получение «вида» файла с заданной гранулярностью.
 *
 * @param filePath относительный путь к файлу в проекте
 * @param granularity уровень детализации; по умолчанию [CodeGranularity.FULL]
 * @param elementPath путь к конкретному элементу — обязателен при [CodeGranularity.ELEMENT]
 */
data class CodeViewRequest(
    val filePath: String,
    val granularity: CodeGranularity = CodeGranularity.FULL,
    val elementPath: String? = null
)
```

### `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/code/CodeView.kt`

```kotlin
package com.maxvibes.domain.model.code

/**
 * Результат обработки [CodeViewRequest]: готовый текст для вставки в промпт.
 *
 * @param filePath относительный путь к файлу
 * @param granularity какой вид был запрошен (для отладки и логирования)
 * @param content текстовое содержимое, соответствующее запрошенной гранулярности
 */
data class CodeView(
    val filePath: String,
    val granularity: CodeGranularity,
    val content: String
)
```

## После шага

### Проверка компиляции
```bash
    ./ gradlew : maxvibes -domain:compileKotlin
```
Должно завершиться без ошибок .

### Unit - тесты(опционально, для уверенности)
Data classes и enum — нет логики, тестировать нечего . Достаточно компиляции.

### Smoke test
        Открыть проект в IDE, убедиться что новые файлы видны в `maxvibes-domain`
и не подчёркнуты красным .
