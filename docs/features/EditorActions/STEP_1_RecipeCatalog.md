# STEP 1 — EditorRecipeCatalog(application)

Цель: чистый каталог рецептов + подстановка плейсхолдеров . Без IntelliJ SDK, тестируется Gradle .

## Новый файл
        `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/EditorRecipeCatalog.kt`

```kotlin
package com.maxvibes.application.service

/**
 * A prepared chat message template bound to an editor gesture.
 * Pure data + pure substitution — no IntelliJ dependencies, Gradle-testable.
 */
data class EditorRecipe(
    val id: String,
    val title: String,
    val template: String,
    /** When true the action refuses to run without a resolved element path. */
    val requiresElement: Boolean = true
)

object EditorRecipeCatalog {

    const val VAR_ELEMENT_PATH = "{{elementPath}}"
    const val VAR_FILE_PATH = "{{filePath}}"
    const val VAR_ELEMENT_NAME = "{{elementName}}"

    val recipes: List<EditorRecipe> = listOf(
        EditorRecipe(
            id = "characterize",
            title = "Feathers: Characterization Tests",
            template = """
                Задача: характеризационные тесты (Физерс, before change) для {{elementPath}}.
                Сначала запроси через requestedViews: ELEMENT для этого элемента и USAGES для него же — тесты должны зафиксировать фактическое поведение, включая краевые случаи из реальных вызовов.
                Конвенции проекта: JUnit5 + MockK, coEvery для suspend, runBlocking (не runTest), файл в src/test/kotlin соответствующего модуля.
                Производственный код НЕ менять — только новый тестовый файл.
            """.trimIndent()
        ),
        EditorRecipe(
            id = "seam",
            title = "Feathers: Find Seams (analysis only)",
            template = """
                Проанализируй {{elementPath}} на предмет швов (seams) по Физерсу. Только анализ — без modifications и commands.
                Сначала запроси USAGES и CALLERS для {{elementPath}}, плюс ELEMENT для тела.
                Затем: перечисли жёсткие зависимости; для каждой предложи технику разрыва (Extract Interface / Parameterize Constructor / Extract and Override) с trade-off; укажи, какой шов дешевле всего ввести первым.
            """.trimIndent()
        ),
        EditorRecipe(
            id = "sprout",
            title = "Feathers: Sprout Method",
            template = """
                Sprout Method по Физерсу для {{elementPath}}: новую логику добавляем отдельной функцией с тестом, тело {{elementName}} меняем минимально (одна строка вызова).
                Сначала запроси ELEMENT для {{elementPath}}.
                Что добавляем: 
            """.trimIndent()
        ),
        EditorRecipe(
            id = "extract-override",
            title = "Feathers: Extract and Override",
            template = """
                Подготовь {{elementPath}} к тестированию через Extract and Override (Физерс).
                Запроси ELEMENT; найди в теле трудную зависимость (I/O, время, статика), вынеси её в protected open метод, покажи тестовый сабкласс с override и один тест на его основе.
            """.trimIndent()
        ),
        EditorRecipe(
            id = "explain",
            title = "Explain Element",
            template = """
                Объясни {{elementPath}}: назначение, как работает, неочевидные места и инварианты. Запроси ELEMENT (при необходимости CALLERS на 1 уровень). Без modifications.
            """.trimIndent()
        ),
        EditorRecipe(
            id = "smells",
            title = "Find Smells / Risks",
            template = """
                Ревью {{elementPath}}: смеллы, риски, нарушение SRP, скрытые побочные эффекты, обработка ошибок. Запроси ELEMENT и OUTLINE содержащего класса. Только анализ с приоритезацией находок, без modifications.
            """.trimIndent()
        ),
        EditorRecipe(
            id = "kdoc",
            title = "Write KDoc",
            template = """
                Напиши KDoc для {{elementPath}} в стиле существующих доков проекта. Запроси ELEMENT, затем верни один REPLACE_ELEMENT: тот же код без изменений + KDoc сверху.
            """.trimIndent()
        ),
        EditorRecipe(
            id = "unittest",
            title = "Write Unit Test",
            template = """
                Напиши unit-тесты для {{elementPath}}. Запроси ELEMENT и USAGES. Конвенции: JUnit5 + MockK, coEvery для suspend, runBlocking; файл в src/test/kotlin нужного модуля. Производственный код не трогать.
            """.trimIndent()
        )
    )

    fun byId(id: String): EditorRecipe? = recipes.firstOrNull { it.id == id }

    /** elementPath = null → подставляется filePath (файловый фолбэк для requiresElement=false). */
    fun compose(recipe: EditorRecipe, elementPath: String?, filePath: String, elementName: String?): String =
        recipe.template
            .replace(VAR_ELEMENT_PATH, elementPath ?: filePath)
            .replace(VAR_FILE_PATH, filePath)
            .replace(VAR_ELEMENT_NAME, elementName ?: filePath.substringAfterLast('/'))
}
```

## Тест
`maxvibes-application/src/test/kotlin/com/maxvibes/application/service/EditorRecipeCatalogTest.kt`

Проверки:
1.compose для каждого рецепта с elementPath не оставляет {
    {
        в результате .
        2.compose с elementPath = null подставляет filePath.3.id всех рецептов уникальны, title и template не пустые.

        ## Проверка шага
        `gradlew.bat :maxvibes-application:test` — зелёный; проект компилируется .
