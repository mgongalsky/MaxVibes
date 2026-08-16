package com.maxvibes.application.port.input

import com.maxvibes.domain.model.check.CheckCancellation
import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckProgressSink
import com.maxvibes.domain.model.check.CheckRequest

interface RunCheckUseCase {

    /**
     * Запускает проверку. Вызывается UI-слоем ПОСЛЕ одобрения (или по политике автономии).
     *
     * @param progress канал промежуточных событий для пузыря проверки.
     * @param cancellation выключатель, которым пузырь обрывает зависший прогон.
     */
    suspend fun run(
        request: CheckRequest,
        progress: CheckProgressSink = CheckProgressSink.NOOP,
        cancellation: CheckCancellation = CheckCancellation()
    ): CheckExecution

    /** Форматирует результат (или отказ) для отправки обратно агенту. */
    fun formatForLlm(execution: CheckExecution, maxIssues: Int = DEFAULT_MAX_ISSUES): String

    companion object {
        /**
         * Полсотни ошибок компиляции означают, что сломано что-то структурное;
         * остальные только сожгут контекст, не добавив информации.
         */
        const val DEFAULT_MAX_ISSUES = 50
    }
}
