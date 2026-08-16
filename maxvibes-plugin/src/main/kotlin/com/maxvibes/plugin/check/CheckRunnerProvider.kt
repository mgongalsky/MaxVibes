package com.maxvibes.plugin.check

import com.intellij.openapi.project.Project
import com.maxvibes.application.port.output.CheckRunnerPort
import com.maxvibes.domain.model.check.CheckCancellation
import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckKind
import com.maxvibes.domain.model.check.CheckProgressSink
import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.check.CheckStatus

/**
 * Собирает раннер проверок для текущей IDE.
 *
 * Контракт изоляции — тот же, что у KotlinAdapterProvider: [JvmBuildCheckRunner]
 * ссылается на compiler API, которого нет в IDE без JVM-поддержки, поэтому его
 * класс не должен загружаться раньше, чем подтверждено наличие API. Проверяем
 * сам вызываемый класс, а не косвенный признак вроде наличия языка.
 *
 * [ScopedTestCheckRunner] в гейте не нуждается: он построен на платформенном
 * execution API, а Java-PSI прячет во вложенном классе с отложенной загрузкой.
 */
object CheckRunnerProvider {

    fun forProject(project: Project): CheckRunnerPort {
        val runners = buildList {
            if (hasCompilerApi()) add(JvmBuildCheckRunner(project))
            add(ScopedTestCheckRunner(project))
        }
        return CompositeCheckRunner(runners)
    }

    private fun hasCompilerApi(): Boolean = try {
        Class.forName("com.intellij.openapi.compiler.CompilerManager")
        true
    } catch (_: Throwable) {
        false
    }
}

/**
 * Раздаёт запрос первому раннеру, который поддерживает нужный вид проверки.
 *
 * Неподдержанный вид отвечает честным [CheckStatus.UNSUPPORTED], чтобы агент
 * увидел причину и откатился на терминальную команду, а не получил исключение
 * внутри хода.
 */
class CompositeCheckRunner(private val runners: List<CheckRunnerPort>) : CheckRunnerPort {

    override fun supports(kind: CheckKind): Boolean = runners.any { it.supports(kind) }

    override suspend fun run(
        request: CheckRequest,
        progress: CheckProgressSink,
        cancellation: CheckCancellation
    ): CheckExecution {
        val runner = runners.firstOrNull { it.supports(request.kind) }
            ?: return CheckExecution(request = request, status = CheckStatus.UNSUPPORTED)
        return runner.run(request, progress, cancellation)
    }
}
