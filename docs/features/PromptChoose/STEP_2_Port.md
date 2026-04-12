# Step 2: Application Port — SpecificPromptRepository

## Цель

Добавить порт `SpecificPromptRepository` в application layer — интерфейс для загрузки специфических промптов .

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/SpecificPromptRepository.kt` | CREATE — новый интерфейс |

## Задание

Пакет: `com.maxvibes.application.port.output`

```kotlin
package com.maxvibes.application.port.output

import com . maxvibes . domain . model . interaction . SpecificPrompt

/**
 * Port for loading task-scoped specific prompts from the project filesystem.
 *
 * Implementations read `.md` / `.txt` files from a designated directory
 * (e.g. `.maxvibes/prompts/specific/`).
 *
 * Contract:
 * - Returns an empty list if the directory does not exist — never throws.
 * - [loadByName] returns null if no file with that name exists.
 * - File name without extension is used as the prompt name.
 */
interface SpecificPromptRepository {

    /**
     * Loads all available specific prompts from the project.
     * Returns an empty list if the prompts directory does not exist.
     */
    fun loadAll(): List<SpecificPrompt>

    /**
     * Loads a single prompt by its display name (file name without extension).
     * Returns null if not found.
     */
    fun loadByName(name: String): SpecificPrompt?
}
```

## Проверка

```bash
    ./ gradlew : maxvibes -application:build
```
