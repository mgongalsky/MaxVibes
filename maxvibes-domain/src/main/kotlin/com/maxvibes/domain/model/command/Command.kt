package com.maxvibes.domain.model.command

/**
 * Запрос на выполнение shell-команды от LLM.
 * Terminal escape hatch: для того, чего инструменты плагина не умеют
 * (сборка, тесты, git), или как обход, когда PSI-операция сглючила.
 */
data class CommandRequest(
    /** Команда целиком, как для терминала. Выполняется из корня проекта. */
    val command: String,
    /** Обоснование от LLM — показывается пользователю рядом с командой. */
    val reason: String? = null,
    val timeoutSec: Int = DEFAULT_TIMEOUT_SEC
) {
    companion object {
        const val DEFAULT_TIMEOUT_SEC = 120
    }
}

enum class CommandStatus {
    SUCCESS,    // exit code 0
    FAILED,     // ненулевой exit code
    TIMEOUT,
    ERROR,      // не удалось запустить процесс
    DECLINED    // пользователь отклонил
}

data class CommandExecution(
    val request: CommandRequest,
    val status: CommandStatus,
    val exitCode: Int? = null,
    /** Полный combined stdout+stderr. Обрезка до хвоста — при отправке LLM. */
    val output: String = "",
    val durationMs: Long = 0,
    /** Комментарий пользователя при отказе */
    val declineComment: String? = null
)