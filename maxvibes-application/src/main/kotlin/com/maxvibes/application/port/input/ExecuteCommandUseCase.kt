package com.maxvibes.application.port.input

import com.maxvibes.domain.model.command.CommandExecution
import com.maxvibes.domain.model.command.CommandRequest

interface ExecuteCommandUseCase {
    /** Выполняет команду. Вызывается UI-слоем ПОСЛЕ подтверждения пользователем. */
    suspend fun execute(request: CommandRequest): CommandExecution

    /** Неблокирующие предупреждения для бейджей рядом с командой. */
    fun warningsFor(request: CommandRequest): List<String>

    /** Форматирует результат (или отказ) для отправки обратно LLM. */
    fun formatForLlm(execution: CommandExecution, tailLines: Int = 200): String
}