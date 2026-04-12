# Step 4: Plugin Adapter — FileSpecificPromptRepository

## Цель

Реализовать `SpecificPromptRepository` в plugin -модуле.Читает файлы из
`.maxvibes/prompts/specific/` через `java.io.File`(не IntelliJ VFS — проще и тестируемее).

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/FileSpecificPromptRepository.kt` | CREATE |

## Задание

Пакет: `com.maxvibes.plugin.service`

```kotlin
package com.maxvibes.plugin.service

import com . maxvibes . application . port . output . SpecificPromptRepository
        import com . maxvibes . domain . model . interaction . SpecificPrompt
        import java . io . File

/**
 * Reads task-scoped specific prompts from `{projectDir}/.maxvibes/prompts/specific/`.
 *
 * Filename without extension = prompt name.
 * Supports `.md` and `.txt` extensions.
 * Returns empty list silently if the directory does not exist.
 * Never throws — all I/O errors are swallowed and result in the prompt being skipped.
 */
class FileSpecificPromptRepository(private val specificPromptsDir: File) : SpecificPromptRepository {

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("md", "txt")

        /**
         * Creates a repository pointing at `{projectBasePath}/.maxvibes/prompts/specific/`.
         */
        fun forProject(projectBasePath: String): FileSpecificPromptRepository =
            FileSpecificPromptRepository(
                File(projectBasePath, ".maxvibes/prompts/specific")
            )
    }

    override fun loadAll(): List<SpecificPrompt> {
        if (!specificPromptsDir.exists() || !specificPromptsDir.isDirectory) return emptyList()
        return specificPromptsDir
            .listFiles { f -> f.isFile && f.extension in SUPPORTED_EXTENSIONS }
            .orEmpty()
            .sortedBy { it.nameWithoutExtension }
            .mapNotNull { readPromptFile(it) }
    }

    override fun loadByName(name: String): SpecificPrompt? {
        if (!specificPromptsDir.exists()) return null
        val file = SUPPORTED_EXTENSIONS
            .map { ext -> File(specificPromptsDir, "$name.$ext") }
            .firstOrNull { it.exists() }
            ?: return null
        return readPromptFile(file)
    }

    private fun readPromptFile(file: File): SpecificPrompt? {
        return try {
            SpecificPrompt(
                name = file.nameWithoutExtension,
                content = file.readText(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            null
        }
    }
}
```

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:build
```

Примечание: `FileSpecificPromptRepository` не является IntelliJ -сервисом(`@Service`) —
он создаётся вручную в `MaxVibesService`(шаг 9).Это позволяет тестировать его
        без IDE, передавая любой `File` в конструктор.
