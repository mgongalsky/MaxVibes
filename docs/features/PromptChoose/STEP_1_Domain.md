# Step 1: Domain — SpecificPrompt + ClipboardRequest.specificPrompt

## Цель

Добавить доменную модель `SpecificPrompt` и расширить `ClipboardRequest` полем `specificPrompt: String?`.

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/interaction/SpecificPrompt.kt` | CREATE — новый data class |
| `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/interaction/ClipboardRequest.kt` | MODIFY — добавить поле `specificPrompt` |

## Задание

### 1.Создать SpecificPrompt . kt

        Пакет: `com.maxvibes.domain.model.interaction`

```kotlin
package com.maxvibes.domain.model.interaction

/**
 * A named task-scoped prompt loaded from the project's `.maxvibes/prompts/specific/` directory.
 *
 * @param name    Display name (file name without extension).
 * @param content Full text content of the prompt file.
 */
data class SpecificPrompt(
    val name: String,
    val content: String
)
```

### 2.Расширить ClipboardRequest

        Перед добавлением прочитать файл `ClipboardRequest.kt` целиком (`FULL`).Добавить в конец списка параметров data class :
    ```kotlin
/**
 * Optional task-scoped prompt injected alongside the system instruction.
 * Null when "Just Code" mode is active (no specific prompt selected).
 */
val specificPrompt: String? = null
```

Обязательно * * последним полем * * с дефолтным значением `null` — для backward compatibility
со всеми существующими вызовами конструктора `ClipboardRequest(...)` в кодовой базе.

## Проверка

```bash
    ./ gradlew : maxvibes -domain:build
```

Должно компилироваться без ошибок . Никакие существующие вызовы `ClipboardRequest(...)` не сломаются,
так как новое поле имеет дефолт .
