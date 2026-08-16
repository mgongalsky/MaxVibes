package com.maxvibes.domain.model.check

/**
 * Одна адресуемая цель внутри круга тестов.
 *
 * Цели намеренно описаны в терминах кода (класс, метод, пакет, файл), а не в
 * терминах run configuration: имя конфигурации — это свойство настроек IDE
 * конкретного пользователя, агент его не знает и знать не должен.
 */
sealed interface TestTarget {

    /** Человекочитаемое описание для пузыря проверки. */
    val description: String

    /** Все тесты проекта. */
    object AllTests : TestTarget {
        override val description: String = "all tests"
    }

    data class TestClass(val fqn: String) : TestTarget {
        override val description: String get() = fqn.substringAfterLast('.')
    }

    data class TestMethod(val classFqn: String, val methodName: String) : TestTarget {
        override val description: String get() = "${classFqn.substringAfterLast('.')}.$methodName"
    }

    data class TestPackage(val packageName: String, val recursive: Boolean) : TestTarget {
        override val description: String get() = packageName + if (recursive) ".**" else ".*"
    }

    /** Путь к файлу относительно корня проекта. */
    data class TestFile(val path: String) : TestTarget {
        override val description: String get() = path.substringAfterLast('/')
    }

    /** Токен, который не удалось разобрать: раннер обязан ответить понятной ошибкой. */
    data class Unknown(val raw: String) : TestTarget {
        override val description: String get() = "?$raw"
    }
}

/**
 * Разобранный `scope` запроса [CheckKind.TESTS].
 *
 * @param raw исходная строка — нужна в сообщениях об ошибке, чтобы пользователь
 *   увидел ровно то, что прислал агент.
 */
data class TestScope(
    val targets: List<TestTarget>,
    val raw: String? = null
) {
    val isAll: Boolean get() = targets.any { it is TestTarget.AllTests }

    val unknownTargets: List<TestTarget.Unknown> get() = targets.filterIsInstance<TestTarget.Unknown>()

    /** Короткая подпись круга тестов для пузыря: «all tests», «BarTest», «com.foo.**, BazTest». */
    val description: String
        get() = when {
            targets.isEmpty() -> TestTarget.AllTests.description
            isAll -> TestTarget.AllTests.description
            else -> targets.joinToString(", ") { it.description }
        }
}

/**
 * Переводит строку `scope` в [TestScope].
 *
 * Грамматика (цели разделяются запятой, точкой с запятой или переводом строки):
 * - пусто, `all`, `*` — все тесты проекта;
 * - `com.foo.BarTest` — класс: последний сегмент начинается с заглавной буквы;
 * - `com.foo.BarTest#name` — один тестовый метод;
 * - `com.foo.bar` или `com.foo.bar.**` — пакет вместе с подпакетами;
 * - `com.foo.bar.*` — только сам пакет;
 * - `src/test/kotlin/com/foo/BarTest.kt` — файл.
 *
 * Эвристика «заглавная буква = класс» повторяет поведение IDEA, чтобы агенту не
 * пришлось учить отдельное правило. Разбор никогда не бросает исключение:
 * непонятный токен превращается в [TestTarget.Unknown] и доезжает до раннера,
 * который отвечает пользователю конкретной подсказкой вместо тихого запуска
 * чего-нибудь постороннего.
 */
object TestScopeParser {

    private val ALL_ALIASES = setOf("all", "*", "**", "all tests", "project", "whole project")
    private val DOTTED_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val FILE_SUFFIXES = listOf(".kt", ".java", ".py", ".kts")

    fun parse(raw: String?): TestScope {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty() || trimmed.lowercase() in ALL_ALIASES) {
            return TestScope(listOf(TestTarget.AllTests), trimmed)
        }
        val targets = trimmed.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseTarget(it) }
        return TestScope(targets.ifEmpty { listOf(TestTarget.AllTests) }, trimmed)
    }

    private fun parseTarget(token: String): TestTarget = when {
        token.lowercase() in ALL_ALIASES -> TestTarget.AllTests
        looksLikePath(token) -> TestTarget.TestFile(token.replace('\\', '/'))
        token.contains('#') -> parseMethod(token)
        token.endsWith(".**") -> parsePackage(token.removeSuffix(".**"), recursive = true, token = token)
        token.endsWith(".*") -> parsePackage(token.removeSuffix(".*"), recursive = false, token = token)
        !DOTTED_NAME.matches(token) -> TestTarget.Unknown(token)
        startsUpper(token.substringAfterLast('.')) -> TestTarget.TestClass(token)
        else -> TestTarget.TestPackage(token, recursive = true)
    }

    private fun parseMethod(token: String): TestTarget {
        val classFqn = token.substringBefore('#').trim()
        val method = token.substringAfter('#').trim()
        return if (method.isNotEmpty() && DOTTED_NAME.matches(classFqn)) {
            TestTarget.TestMethod(classFqn, method)
        } else {
            TestTarget.Unknown(token)
        }
    }

    private fun parsePackage(name: String, recursive: Boolean, token: String): TestTarget =
        if (name.isNotEmpty() && DOTTED_NAME.matches(name)) {
            TestTarget.TestPackage(name, recursive)
        } else {
            TestTarget.Unknown(token)
        }

    private fun looksLikePath(token: String): Boolean =
        token.contains('/') || token.contains('\\') || FILE_SUFFIXES.any { token.endsWith(it, ignoreCase = true) }

    private fun startsUpper(segment: String): Boolean = segment.firstOrNull()?.isUpperCase() == true
}
