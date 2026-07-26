package com.maxvibes.plugin.ui

import com.maxvibes.application.port.input.ExecuteCommandUseCase
import com.maxvibes.domain.model.command.CommandExecution
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.command.CommandStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.service.MaxVibesLogger

/**
 * Command-turn state machine extracted from [ChatMessageController].
 *
 * Owns the lifecycle of one command batch: renders command bubbles (plus a
 * Run all / Decline all bar for batches), tracks Run/Decline resolutions,
 * executes approved commands via the injected [executeAsync], and reports the
 * finished batch through [onBatchComplete]. Run all executes sequentially and
 * stops at the first non-zero exit code, declining the rest.
 *
 * Threading is intentionally externalized: [executeAsync] is expected to run the
 * command off the EDT and invoke its callback back on the EDT, so this class
 * stays synchronous and unit-testable.
 */
class CommandTurnCoordinator(
    private val executeCommandUseCase: ExecuteCommandUseCase,
    private val callbacks: ChatPanelCallbacks,
    private val addSystemMessage: (sessionId: String, text: String) -> Unit,
    private val activeSessionId: () -> String,
    private val executeAsync: (request: CommandRequest, onDone: (CommandExecution) -> Unit) -> Unit,
    private val onBatchComplete: (sessionId: String, mode: InteractionMode, resultsForLlm: String) -> Unit
) {

    /** One command of the current batch with its UI handle and resolution state. */
    private class CommandItem(
        val request: CommandRequest,
        var view: CommandBlockView? = null,
        var started: Boolean = false,
        var resolved: Boolean = false
    )

    /** Accumulates Run/Decline outcomes for the current turn's command batch. */
    private class CommandTurn(
        val sessionId: String,
        val mode: InteractionMode,
        val items: MutableList<CommandItem> = mutableListOf(),
        val executions: MutableList<CommandExecution> = mutableListOf(),
        var runAllActive: Boolean = false,
        var batchBar: CommandBatchBarView? = null
    )

    private var commandTurn: CommandTurn? = null

    /**
     * Renders command blocks (plus a Run all / Decline all bar for batches) and keeps
     * input locked until every command is resolved. When the batch completes, results
     * are reported through [onBatchComplete] — see [recordExecution].
     */
    fun presentCommands(commands: List<CommandRequest>, sessionId: String, mode: InteractionMode) {
        if (commands.isEmpty()) return
        MaxVibesLogger.info("Controller", "presentCommands", mapOf("count" to commands.size, "mode" to mode.name))
        val turn = CommandTurn(sessionId, mode)
        commandTurn = turn
        callbacks.setInputEnabled(false)
        callbacks.setStatus("\u26A1 ${commands.size} command(s) awaiting approval")
        if (commands.size > 1) {
            turn.batchBar = callbacks.addCommandBatchBar(
                count = commands.size,
                onRunAll = { startRunAll() },
                onDeclineAll = { declineAllRemaining(null) }
            )
        }
        commands.forEach { cmd ->
            val item = CommandItem(cmd)
            turn.items.add(item)
            val warnings = executeCommandUseCase.warningsFor(cmd)
            item.view = callbacks.addCommandBubble(
                command = cmd.command,
                reason = cmd.reason,
                warnings = warnings,
                onRun = { runCommand(item) },
                onDecline = { comment -> declineItem(item, comment) }
            )
        }
    }

    /** Runs every unresolved command sequentially, stopping at the first non-zero exit code. */
    private fun startRunAll() {
        val turn = commandTurn ?: return
        if (turn.runAllActive) return
        turn.runAllActive = true
        turn.batchBar?.dismiss()
        turn.items.filter { !it.resolved && !it.started }.forEach { it.view?.setQueued() }
        // If a manually started command is still running, its completion hook continues the chain.
        if (turn.items.none { it.started && !it.resolved }) runNextQueued()
    }

    private fun runNextQueued() {
        val turn = commandTurn ?: return
        val next = turn.items.firstOrNull { !it.resolved && !it.started } ?: return
        runCommand(next)
    }

    /** Declines every unresolved command; used by Decline all and by the run-all failure stop. */
    private fun declineAllRemaining(comment: String?) {
        val turn = commandTurn ?: return
        turn.batchBar?.dismiss()
        turn.items.filter { !it.resolved && !it.started }.toList().forEach { declineItem(it, comment) }
    }

    private fun declineItem(item: CommandItem, comment: String?) {
        if (item.resolved) return
        item.resolved = true
        item.view?.setDeclined(comment)
        addSystemMessage(
            commandTurn?.sessionId ?: activeSessionId(),
            "\u2716 Declined: ${item.request.command}" + (comment?.let { " \u2014 $it" } ?: "")
        )
        recordExecution(
            CommandExecution(request = item.request, status = CommandStatus.DECLINED, declineComment = comment)
        )
    }

    /** Executes one approved command via [executeAsync] and records its outcome. */
    private fun runCommand(item: CommandItem) {
        if (item.resolved || item.started) return
        item.started = true
        item.view?.setRunning()
        callbacks.setStatus("\u26A1 Running: ${item.request.command.take(50)}")
        executeAsync(item.request) { execution ->
            val headline = when (execution.status) {
                CommandStatus.SUCCESS -> "\u2705 exit 0 \u00B7 ${execution.durationMs / 1000}s"
                CommandStatus.FAILED -> "\u274C exit ${execution.exitCode} \u00B7 ${execution.durationMs / 1000}s"
                CommandStatus.TIMEOUT -> "\u23F1 Timeout after ${item.request.timeoutSec}s"
                CommandStatus.ERROR -> "\u274C Failed to start"
                CommandStatus.DECLINED -> "\u2716 Declined"
            }
            item.resolved = true
            item.view?.setResult(headline, execution.output, execution.status == CommandStatus.SUCCESS)
            addSystemMessage(
                commandTurn?.sessionId ?: activeSessionId(),
                "\u26A1 ${item.request.command} \u2192 $headline"
            )
            val turn = commandTurn
            recordExecution(execution)
            if (turn != null && turn.runAllActive && commandTurn === turn) {
                if (execution.status == CommandStatus.SUCCESS) {
                    runNextQueued()
                } else {
                    declineAllRemaining("skipped: previous command failed")
                }
            }
        }
    }

    /** Records one resolved command; when the batch is complete, reports it via [onBatchComplete]. */
    private fun recordExecution(execution: CommandExecution) {
        val turn = commandTurn ?: return
        turn.executions.add(execution)
        val remaining = turn.items.size - turn.executions.size
        if (remaining > 0) {
            callbacks.setStatus("\u26A1 $remaining command(s) awaiting approval")
            return
        }
        turn.batchBar?.dismiss()
        commandTurn = null
        val formatted = turn.executions.joinToString("\n\n---\n\n") {
            executeCommandUseCase.formatForLlm(it)
        }
        MaxVibesLogger.info(
            "Controller", "commandTurn complete",
            mapOf("mode" to turn.mode.name, "n" to turn.executions.size)
        )
        callbacks.setStatus("\u26A1 Sending command results...")
        onBatchComplete(turn.sessionId, turn.mode, formatted)
    }
}
