package com.maxvibes.application.port.output

import com.maxvibes.domain.model.check.CheckCancellation
import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckKind
import com.maxvibes.domain.model.check.CheckProgressSink
import com.maxvibes.domain.model.check.CheckRequest

/**
 * Запуск проверок средствами конкретной IDE.
 *
 * Значения по умолчанию объявлены здесь, а не в реализациях: вызывающая сторона
 * может по-прежнему писать `run(request)`, но каждая реализация обязана принять
 * оба параметра — иначе компилятор промолчит про раннер, который забыл про
 * прогресс и отмену.
 */
interface CheckRunnerPort {

    fun supports(kind: CheckKind): Boolean

    /**
     * @param progress канал живых событий: пользователь должен видеть, что проверка не зависла.
     * @param cancellation раннер регистрирует здесь способ прервать себя.
     */
    suspend fun run(
        request: CheckRequest,
        progress: CheckProgressSink = CheckProgressSink.NOOP,
        cancellation: CheckCancellation = CheckCancellation()
    ): CheckExecution
}
