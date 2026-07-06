package com.maxvibes.plugin.command

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.maxvibes.application.port.output.CommandRunnerPort
import com.maxvibes.domain.model.command.CommandExecution
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.command.CommandStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProcessCommandRunner(private val project: Project) : CommandRunnerPort {

    override suspend fun run(request: CommandRequest): CommandExecution = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val cli = buildCommandLine(request.command)
                .withWorkDirectory(project.basePath)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withCharset(Charsets.UTF_8)

            val output = CapturingProcessHandler(cli).runProcess(request.timeoutSec * 1000)
            val elapsed = System.currentTimeMillis() - start
            val combined = buildString {
                append(output.stdout)
                if (output.stderr.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append(output.stderr)
                }
            }
            when {
                output.isTimeout -> CommandExecution(
                    request, CommandStatus.TIMEOUT, null,
                    combined + "\n[timed out after ${request.timeoutSec}s]", elapsed
                )
                output.exitCode == 0 -> CommandExecution(request, CommandStatus.SUCCESS, 0, combined, elapsed)
                else -> CommandExecution(request, CommandStatus.FAILED, output.exitCode, combined, elapsed)
            }
        } catch (e: Exception) {
            CommandExecution(
                request, CommandStatus.ERROR, null,
                "Failed to start process: ${e.message}",
                System.currentTimeMillis() - start
            )
        }
    }

    private fun buildCommandLine(command: String): GeneralCommandLine =
        if (SystemInfo.isWindows) {
            GeneralCommandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command)
        } else {
            GeneralCommandLine("/bin/sh", "-c", command)
        }
}