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
    private val commandView: CommandView,
    private val callbacks: InputStatusView,
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

    fun presentCommands(commands: List<CommandRequest>, sessionId: String, mode: InteractionMode) {
        if (commands.isEmpty()) return
        commandTurn?.batchBar?.dismiss()
        MaxVibesLogger.info("Controller", "presentCommands", mapOf("count" to commands.size, "mode" to mode.name))
        val turn = CommandTurn(sessionId, mode)
        commandTurn = turn
        callbacks.setInputEnabled(false)
        callbacks.setStatus("⚡ ${commands.size} command(s) awaiting approval")
        if (commands.size > 1) {
            turn.batchBar = commandView.addCommandBatchBar(
                count = commands.size,
                onRunAll = { startRunAll(turn) },
                onDeclineAll = { declineAllRemaining(turn, null) }
            )
        }
        commands.forEach { command ->
            val item = CommandItem(command)
            turn.items.add(item)
            val warnings = executeCommandUseCase.warningsFor(command)
            item.view = commandView.addCommandBubble(
                command = command.command,
                reason = command.reason,
                warnings = warnings,
                onRun = { runCommand(turn, item) },
                onDecline = { comment -> declineItem(turn, item, comment) }
            )
        }
    }

    private fun startRunAll(turn: CommandTurn) {
        if (commandTurn !== turn || turn.runAllActive) return
        turn.runAllActive = true
        turn.batchBar?.dismiss()
        turn.items.filter { !it.resolved && !it.started }.forEach { it.view?.setQueued() }
        if (turn.items.none { it.started && !it.resolved }) {
            runNextQueued(turn)
        }
    }

    private fun runNextQueued(turn: CommandTurn) {
        if (commandTurn !== turn) return
        val next = turn.items.firstOrNull { !it.resolved && !it.started } ?: return
        runCommand(turn, next)
    }

    private fun declineAllRemaining(turn: CommandTurn, comment: String?) {
        if (commandTurn !== turn) return
        turn.batchBar?.dismiss()
        turn.items
            .filter { !it.resolved && !it.started }
            .toList()
            .forEach { declineItem(turn, it, comment) }
    }

    private fun declineItem(turn: CommandTurn, item: CommandItem, comment: String?) {
        if (commandTurn !== turn || item !in turn.items || item.resolved || item.started) return
        item.resolved = true
        item.view?.setDeclined(comment)
        addSystemMessage(
            commandTurn?.sessionId ?: activeSessionId(),
            "✖ Declined: ${item.request.command}" + (comment?.let { " — $it" } ?: "")
        )
        recordExecution(
            turn,
            CommandExecution(
                request = item.request,
                status = CommandStatus.DECLINED,
                declineComment = comment
            )
        )
    }

    private fun runCommand(turn: CommandTurn, item: CommandItem) {
        if (commandTurn !== turn || item !in turn.items || item.resolved || item.started) return
        item.started = true
        item.view?.setRunning()
        callbacks.setStatus("⚡ Running: ${item.request.command.take(50)}")
        executeAsync(item.request) completion@{ execution ->
            if (commandTurn !== turn || item.resolved) return@completion
            val headline = when (execution.status) {
                CommandStatus.SUCCESS -> "✅ exit 0 · ${execution.durationMs / 1000}s"
                CommandStatus.FAILED -> "❌ exit ${execution.exitCode} · ${execution.durationMs / 1000}s"
                CommandStatus.TIMEOUT -> "⏱ Timeout after ${item.request.timeoutSec}s"
                CommandStatus.ERROR -> "❌ Failed to start"
                CommandStatus.DECLINED -> "✖ Declined"
            }
            item.resolved = true
            item.view?.setResult(headline, execution.output, execution.status == CommandStatus.SUCCESS)
            addSystemMessage(
                commandTurn?.sessionId ?: activeSessionId(),
                "⚡ ${item.request.command} → $headline"
            )
            recordExecution(turn, execution)
            if (turn.runAllActive && commandTurn === turn) {
                if (execution.status == CommandStatus.SUCCESS) {
                    runNextQueued(turn)
                } else {
                    declineAllRemaining(turn, "skipped: previous command failed")
                }
            }
        }
    }

    private fun recordExecution(turn: CommandTurn, execution: CommandExecution) {
        if (commandTurn !== turn) return
        turn.executions.add(execution)
        val remaining = turn.items.size - turn.executions.size
        if (remaining > 0) {
            callbacks.setStatus("⚡ $remaining command(s) awaiting approval")
            return
        }
        turn.batchBar?.dismiss()
        commandTurn = null
        val lineFeed = 10.toChar().toString()
        val formatted = turn.executions.joinToString(
            lineFeed + lineFeed + "---" + lineFeed + lineFeed
        ) {
            executeCommandUseCase.formatForLlm(it)
        }
        MaxVibesLogger.info(
            "Controller",
            "commandTurn complete",
            mapOf("mode" to turn.mode.name, "n" to turn.executions.size)
        )
        callbacks.setStatus("⚡ Sending command results...")
        onBatchComplete(turn.sessionId, turn.mode, formatted)
    }
}
