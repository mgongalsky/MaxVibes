# Step 9: DI — MaxVibesService wiring

## Цель

Зарегистрировать `FileSpecificPromptRepository` и `SpecificPromptService` в `MaxVibesService` .

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/service/MaxVibesService.kt` | MODIFY |

**Перед изменениями прочитать файл целиком(`FULL`).* *

## Задание

Добавить два lazy - свойства после `promptPort`:

```kotlin
val specificPromptRepository: SpecificPromptRepository by lazy {
    FileSpecificPromptRepository.forProject(
        project.basePath ?: System.getProperty("user.home")
    )
}

val specificPromptService: SpecificPromptService by lazy {
    SpecificPromptService(specificPromptRepository)
}
```

Импорты для добавления:
```
com.maxvibes.application.port.output.SpecificPromptRepository
com.maxvibes.application.service.SpecificPromptService
com.maxvibes.plugin.service.FileSpecificPromptRepository
```

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:build
```
