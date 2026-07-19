# Step 3: Application Service — SpecificPromptService +Tests

## Цель

Добавить `SpecificPromptService` в application layer и покрыть его тестами.Сервис не зависит от IntelliJ — тестируется через Gradle.

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/SpecificPromptService.kt` | CREATE |
| `maxvibes-application/src/test/kotlin/com/maxvibes/application/service/SpecificPromptServiceTest.kt` | CREATE — тесты |

## Задание

### Сервис

Пакет: `com.maxvibes.application.service`

```kotlin
package com.maxvibes.application.service

import com . maxvibes . application . port . output . SpecificPromptRepository
        import com . maxvibes . domain . model . interaction . SpecificPrompt

/**
 * Application service for managing task-scoped specific prompts.
 *
 * Wraps [SpecificPromptRepository] with convenience methods for the UI layer.
 * Has no IntelliJ dependencies — fully unit-testable via Gradle.
 *
 * "Just Code" is represented as null throughout this service — no special sentinel object.
 */
class SpecificPromptService(private val repository: SpecificPromptRepository) {

    /**
     * Returns all available prompt names for display in the UI dropdown.
     * Does NOT include "Just Code" — that is the UI's responsibility to prepend.
     */
    fun getAvailablePromptNames(): List<String> =
        repository.loadAll().map { it.name }

    /**
     * Resolves a prompt's content by name.
     *
     * @param name Prompt name, or null for "Just Code" mode.
     * @return Prompt content string, or null if name is null or prompt not found.
     *         Null → the `specificPrompt` field is omitted from the JSON request.
     */
    fun resolvePromptContent(name: String?): String? {
        if (name == null) return null
        return repository.loadByName(name)?.content
    }

    /**
     * Validates that a previously selected prompt name still exists on disk.
     * Returns the name if valid, null (Just Code) if the file has been removed.
     */
    fun validatePromptName(name: String?): String? {
        if (name == null) return null
        return if (repository.loadByName(name) != null) name else null
    }
}
```

### Тесты

Конвенции: `runBlocking` если нет suspend, `MockK` для моков, data classes напрямую.

```kotlin
package com.maxvibes.application.service

import com . maxvibes . application . port . output . SpecificPromptRepository
        import com . maxvibes . domain . model . interaction . SpecificPrompt
        import io . mockk . every
        import io . mockk . mockk
        import org . junit . jupiter . api . Assertions . *
        import org . junit . jupiter . api . Test

class SpecificPromptServiceTest {

    private val repository: SpecificPromptRepository = mockk()
    private val service = SpecificPromptService(repository)

    @Test
    fun `getAvailablePromptNames returns names of all prompts`() {
        every { repository.loadAll() } returns listOf(
            SpecificPrompt("Analyze Only", "content1"),
            SpecificPrompt("Refactor(Feathers)- Extract & Override", "content2")
        )
        val names = service.getAvailablePromptNames()
        assertEquals(listOf("Analyze Only", "Refactor(Feathers)- Extract & Override"), names)
    }

    @Test
    fun `getAvailablePromptNames returns empty list when directory missing`() {
        every { repository.loadAll() } returns emptyList()
        assertTrue(service.getAvailablePromptNames().isEmpty())
    }

    @Test
    fun `resolvePromptContent returns null for null name (Just Code)`() {
        assertNull(service.resolvePromptContent(null))
    }

    @Test
    fun `resolvePromptContent returns content when prompt exists`() {
        every { repository.loadByName("Analyze Only") } returns
                SpecificPrompt("Analyze Only", "Do not modify code.")
        assertEquals("Do not modify code.", service.resolvePromptContent("Analyze Only"))
    }

    @Test
    fun `resolvePromptContent returns null when prompt not found`() {
        every { repository.loadByName("Missing") } returns null
        assertNull(service.resolvePromptContent("Missing"))
    }

    @Test
    fun `validatePromptName returns null for null`() {
        assertNull(service.validatePromptName(null))
    }

    @Test
    fun `validatePromptName returns name when file exists`() {
        every { repository.loadByName("Analyze Only") } returns
                SpecificPrompt("Analyze Only", "content")
        assertEquals("Analyze Only", service.validatePromptName("Analyze Only"))
    }

    @Test
    fun `validatePromptName returns null when file no longer exists`() {
        every { repository.loadByName("Deleted Prompt") } returns null
        assertNull(service.validatePromptName("Deleted Prompt"))
    }
}
```

## Проверка

```bash
    ./ gradlew : maxvibes -application:test
```

Все тесты должны пройти .
