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
 * Builds the runners available in the current IDE.
 * Compiler API is optional. ScopedTestCheckRunner uses platform execution APIs;
 * named targets go through platform resolution before a guarded Java PSI branch.
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
