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
     * Смысл строки знает только языковой адаптер: для JVM это имя модуля или
     * FQN тестового класса, для Python — путь, понятный pytest. Домену незачем
     * знать разницу, поэтому здесь одна строка, а не иерархия.
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

enum class CheckStatus {
    /** Собралось / все тесты зелёные. */
    PASSED,

    /** Ошибки компиляции или упавшие тесты. */
    FAILED,

    TIMEOUT,

    /** Проверку не удалось запустить. */
    ERROR,

    /** Пользователь отклонил. */
    DECLINED,

    /** В этой IDE нет языкового адаптера для такой проверки. */
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
