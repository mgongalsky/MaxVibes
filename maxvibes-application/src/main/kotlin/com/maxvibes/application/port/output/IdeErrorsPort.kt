package com.maxvibes.application.port.output

import com.maxvibes.domain.model.code.IdeError
import com.maxvibes.shared.result.Result

/**
 * Порт для получения списка ошибок компиляции напрямую из IDE.
 */
interface IdeErrorsPort {
    /**
     * Возвращает список ошибок компилятора после последней сборки.
     */
    suspend fun getCompilerErrors(): Result<List<IdeError>, Exception>
}