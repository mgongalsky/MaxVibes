package com.maxvibes.application.port.output

import com.maxvibes.domain.model.command.CommandExecution
import com.maxvibes.domain.model.command.CommandRequest

/**
 * Порт выполнения shell-команд.
 * Реализация в plugin-модуле: GeneralCommandLine + CapturingProcessHandler,
 * cwd = корень проекта.
 */
interface CommandRunnerPort {
    suspend fun run(request: CommandRequest): CommandExecution
}