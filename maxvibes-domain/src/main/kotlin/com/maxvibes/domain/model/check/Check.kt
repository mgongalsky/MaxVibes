package com.maxvibes.domain.model.check

/**
 * Вид проверки, которую агент просит выполнить средствами IDE.
 *
 * Один канал с полем kind, а не отдельные поля протокола на каждую проверку:
 * инспекции и линт добавляются сюда, не трогая кодек, approval и UI.
 */
enum class CheckKind {
    /** Компиляция без запуска программы. */
    BUILD,

    /** Прогон тестов. */
    TESTS
}

/**
 * Запрос на проверку средствами IDE — альтернатива терминальной команде.
 */
data class CheckRequest(
    val kind: CheckKind,

    /**
     * Что именно проверять. Null — весь проект.
     *
     * Смысл строки зависит от [kind]. Для [CheckKind.BUILD] это имя модуля.
     * Для [CheckKind.TESTS] это круг тестов: класс, метод, пакет, файл или их
     * список — грамматику разбирает [TestScopeParser], и она же единственный
     * источник правды, дублировать её здесь незачем.
     *
     * Имя run-конфигурации сюда не годится: запуск строится из названного кода,
     * а не из сохранённой конфигурации пользователя.
     */
    val scope: String? = null,

    /** Обоснование от агента — показывается пользователю рядом с проверкой. */
    val reason: String? = null,

    val timeoutSec: Int = DEFAULT_TIMEOUT_SEC
) {
    companion object {
        /** Сборка холодного проекта легко занимает минуты — отсюда запас против команд. */
        const val DEFAULT_TIMEOUT_SEC = 600
    }
}

/**
 * Итог одной проверки.
 *
 * Различаются три вида «не получилось»: [FAILED] — проверка отработала и нашла
 * проблемы, [ERROR] — не смогла даже стартовать, [TIMEOUT] — стартовала и не
 * уложилась в отведённое время. Агенту эта разница важнее самого факта неудачи:
 * в первом случае надо чинить код, в остальных — запрос.
 */
enum class CheckStatus {
    PASSED,
    FAILED,
    TIMEOUT,

    /** Не удалось запустить: не нашлась цель, нет раннера, упало исключение. */
    ERROR,

    /** Пользователь отказался запускать проверку. */
    DECLINED,

    /** Пользователь оборвал уже запущенную проверку из пузыря. */
    CANCELLED,

    /** В этой IDE нет раннера для такого вида проверки. */
    UNSUPPORTED
}

enum class IssueSeverity {
    ERROR,
    WARNING
}

/**
 * Одна ошибка компиляции или упавший тест.
 *
 * Ради этого типа канал и существует: агент получает адрес проблемы, а не хвост
 * лога, из которого его надо выковыривать следующим ходом.
 */
data class CheckIssue(
    val message: String,
    val severity: IssueSeverity = IssueSeverity.ERROR,

    /** Путь относительно корня проекта, если известен. */
    val filePath: String? = null,
    val line: Int? = null,

    /** FQN упавшего теста. Null для BUILD. */
    val testName: String? = null,

    /** Сообщение ассерта или стектрейс — то, что не влезает в [message]. */
    val details: String? = null
)

data class CheckExecution(
    val request: CheckRequest,
    val status: CheckStatus,
    val issues: List<CheckIssue> = emptyList(),

    /** Заполняется только для TESTS. */
    val testsTotal: Int? = null,
    val testsFailed: Int? = null,

    val durationMs: Long = 0,

    /**
     * Сырой вывод — страховка на случай, когда адаптер не смог разобрать
     * результат на [issues]. Пустая строка при успешном разборе.
     */
    val rawOutput: String = "",

    /** Комментарий пользователя при отказе. */
    val declineComment: String? = null
)
