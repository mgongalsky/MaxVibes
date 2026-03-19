# STEP 6 — Автоматические тесты

## Цель

Покрыть тестами всю бизнес -логику фичи . После этого шага `./gradlew test`
должен проходить зелёным по всем модулям .

## Тест 1: CodecTest — `maxvibes-plugin`(или `maxvibes-application`)

**Класс:** `JsonClipboardProtocolCodecTest`
**Тип:** Unit, без IntelliJ, без корутин

        Сценарии(подробно описаны в STEP 3):

```kotlin
@Test
fun `парсит requestedFiles как FULL`()
@Test
fun `парсит requestedViews с гранулярностью SIGNATURES`()
@Test
fun `парсит requestedViews без granularity — дефолт FULL`()
@Test
fun `парсит requestedViews с ELEMENT и elementPath`()
@Test
fun `невалидная granularity — дефолт FULL без исключения`()
@Test
fun `requestedViews побеждает requestedFiles при дубликате пути`()
@Test
fun `оба поля без дубликатов — мёрж в один список`()
```

Паттерн:
```kotlin
val codec = JsonClipboardProtocolCodec()
val json = """{ "message": "test", "requestedViews": [...] }"""
val result = codec.decode(json)
assertEquals(expectedRequests, result.requestedViews)
```

---

## Тест 2: PsiCodeViewRendererTest — `maxvibes-adapter-psi`

**Класс:** `PsiCodeViewRendererTest`
**Тип:** Integration(требует IntelliJ test environment — `LightPlatformTestCase`)
**Runner:** IntelliJ IDEA (не Gradle, из - за javaagent -конфликта)

### Фикстура

Создать тестовый файл `TestSubject.kt` с известной структурой:
```kotlin
package com.example

/** Тестовый класс для проверки рендерера. */
class TestSubject(val name: String) : BaseClass() {

    /** Количество элементов. */
    private val count: Int = 0

    /**
     * Выполняет основную работу.
     * @param input входные данные
     * @return результат обработки
     */
    fun doSomething(input: String): Boolean {
        val result = input.isNotBlank()
        return result && count >= 0
    }

    /** Вспомогательный метод без параметров. */
    private fun helper() {
        println("helper called")
    }
}

/** Функция верхнего уровня. */
fun topLevel(x: Int): String = x.toString()
```

### Тест - кейсы

| Метод | Тест | Что проверяем |
|-------|------|---------------|
| `renderSignatures` | содержит сигнатуру `doSomething` | `assertContains(result, "fun doSomething(input: String): Boolean")` |
| `renderSignatures` | НЕ содержит тело `println` | `assertFalse(result.contains("println"))` |
| `renderSignatures` | содержит package | `assertContains(result, "package com.example")` |
| `renderSignatures` | содержит KDoc | `assertContains(result, "Тестовый класс")` |
| `renderSignatures` | содержит функцию верхнего уровня | `assertContains(result, "fun topLevel")` |
| `renderOutline` | содержит суперкласс | `assertContains(result, "BaseClass")` |
| `renderOutline` | содержит свойство с типом | `assertContains(result, "count: Int")` |
| `renderOutline` | НЕ содержит тела методов | `assertFalse(result.contains("println"))` |
| `renderElement` | возвращает полный текст функции | `assertContains(result, "println(\"helper called\")")` |
| `renderElement` | возвращает полный текст свойства | result == `"private val count: Int = 0"` |

### Шаблон теста
```kotlin
class PsiCodeViewRendererTest : LightPlatformTestCase() {

    private lateinit var renderer: PsiCodeViewRenderer

    override fun setUp() {
        super.setUp()
        renderer = PsiCodeViewRenderer()
    }

    private fun createKtFile(content: String): KtFile {
        return PsiFileFactory.getInstance(project)
            .createFileFromText("Test.kt", KotlinFileType.INSTANCE, content) as KtFile
    }

    @Test
    fun `renderSignatures не включает тела функций`() {
        val ktFile = createKtFile(FIXTURE_CONTENT)
        val result = renderer.renderSignatures(ktFile)
        assertFalse(
            "Тело функции не должно быть в сигнатурах",
            result.contains("println")
        )
        assertTrue(result.contains("fun doSomething(input: String): Boolean"))
    }
    // ... остальные тесты
}
```

---

## Тест 3: Обратная совместимость — smoke

        Не автоматический, но обязательный :

1.Открыть плагин в IDE
        2.Провести диалог в Clipboard mode используя ТОЛЬКО старый `requestedFiles`
3.Убедиться что файлы приходят как раньше (FULL), нет регрессий

        ---

## Запуск тестов

```bash
# Domain и application — через Gradle
        ./ gradlew : maxvibes -domain:test
    ./ gradlew : maxvibes -application:test

# Codec тест — через Gradle(нет IntelliJ зависимости)
    ./ gradlew : maxvibes -plugin:test  # или maxvibes -application если переедет

# PSI renderer тест — через IntelliJ IDEA runner
# (Gradle упадёт из - за kotlinx -coroutines - debug javaagent конфликта)
```
