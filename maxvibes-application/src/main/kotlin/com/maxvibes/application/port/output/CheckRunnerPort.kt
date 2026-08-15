package com.maxvibes.application.port.output

import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckKind
import com.maxvibes.domain.model.check.CheckRequest

/**
 * Порт выполнения проверок средствами IDE — сборки и прогона тестов.
 *
 * Реализация зависит от языка: для JVM это компиляция и запуск тестовой
 * run configuration средствами IntelliJ, для Python — свой инструмент.
 * Протокол, разрешения и UI при этом одни на всех.
 */
interface CheckRunnerPort {

    /**
     * Умеет ли раннер выполнить такую проверку в текущей IDE.
     *
     * Отдельно от [run], потому что ответ нужен до показа проверки
     * пользователю: неподдержанный вид должен честно деградировать, а не
     * выясняться через упавший запуск.
     */
    fun supports(kind: CheckKind): Boolean

    suspend fun run(request: CheckRequest): CheckExecution
}
