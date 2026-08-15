package com.maxvibes.plugin.ui

import com.maxvibes.application.port.input.RunCheckUseCase
import com.maxvibes.domain.model.check.CheckExecution
import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.check.CheckStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.service.MaxVibesLogger

/**
 * Check-turn state machine: the IDE-native counterpart of [CommandTurnCoordinator].
 *
 * Owns the lifecycle of one check batch: renders a bubble per requested build or test
 * run, tracks Run/Decline resolutions, executes approved checks via the injected
 * [executeAsync] and reports the finished batch through [onBatchComplete].
 *
 * Checks always run one at a time, and a non-passing check declines the rest of the
 * batch: a red build makes a following test run report nothing but compilation noise.
 *
 * Threading is intentionally externalized: [executeAsync] is expected to run the check
 * off the EDT and invoke its callback back on the EDT, so this class stays synchronous
 * and unit-testable.
 */
class CheckTurnCoordinator(
    private val runCheckUseCase: RunCheckUseCase,
    private val checkView: CheckView,
    private val callbacks: InputStatusView,
    private val addSystemMessage: (sessionId: String, text: String) -> Unit,
    private val activeSessionId: () -> String,
    private val executeAsync: (request: CheckRequest, onDone: (CheckExecution) -> Unit) -> Unit,
    private val onBatchComplete: (sessionId: String, mode: InteractionMode, resultsForLlm: String) -> Unit
) {

    /** One check of the current batch with its UI handle and resolution state. */
    private class CheckItem(
        val request: CheckRequest,
        var view: CheckBlockView? = null,
        var started: Boolean = false,
        var resolved: Boolean = false
    )

    /** Accumulates Run/Decline outcomes for the current turn's check batch. */
    private class CheckTurn(
        val sessionId: String,
        val mode: InteractionMode,
        val items: MutableList<CheckItem> = mutableListOf(),
        val executions: MutableList<CheckExecution> = mutableListOf(),
        var sequential: Boolean = false
    )

    private var checkTurn: CheckTurn? = null

    fun presentChecks(checks: List<CheckRequest>, sessionId: String, mode: InteractionMode) {
        if (checks.isEmpty()) return
        MaxVibesLogger.info("Controller", "presentChecks", mapOf("count" to checks.size, "mode" to mode.name))
        val turn = CheckTurn(sessionId, mode)
        checkTurn = turn
        callbacks.setInputEnabled(false)
        callbacks.setStatus("\uD83D\uDD27 ${checks.size} check(s) awaiting approval")
        checks.forEach { check ->
            val item = CheckItem(check)
            turn.items.add(item)
            item.view = checkView.addCheckBubble(
                title = titleOf(check),
                reason = check.reason,
                onRun = { runCheck(turn, item) },
                onDecline = { comment -> declineItem(turn, item, comment) }
            )
        }
    }

    /**
     * Starts the pending batch without a human click, when the approval policy allows
     * builds or test runs. Runs the checks sequentially, exactly as a manual Run of each
     * bubble in order would.
     */
    fun runAllAutomatically(sessionId: String) {
        val turn = checkTurn ?: return
        if (turn.sessionId != sessionId) return
        MaxVibesLogger.info("Controller", "auto-run checks", mapOf("count" to turn.items.size))
        callbacks.setStatus("\uD83E\uDD16 Running ${turn.items.size} check(s) automatically...")
        turn.sequential = true
        turn.items.filter { !it.resolved && !it.started }.forEach { it.view?.setQueued() }
        if (turn.items.none { it.started && !it.resolved }) {
            runNextQueued(turn)
        }
    }

    private fun runNextQueued(turn: CheckTurn) {
        if (checkTurn !== turn) return
        val next = turn.items.firstOrNull { !it.resolved && !it.started } ?: return
        runCheck(turn, next)
    }

    private fun declineAllRemaining(turn: CheckTurn, comment: String?) {
        if (checkTurn !== turn) return
        turn.items
            .filter { !it.resolved && !it.started }
            .toList()
            .forEach { declineItem(turn, it, comment) }
    }

    private fun declineItem(turn: CheckTurn, item: CheckItem, comment: String?) {
        if (checkTurn !== turn || item !in turn.items || item.resolved || item.started) return
        item.resolved = true
        item.view?.setDeclined(comment)
        addSystemMessage(
            checkTurn?.sessionId ?: activeSessionId(),
            "\u2716 Declined: ${titleOf(item.request)}" + (comment?.let { " — $it" } ?: "")
        )
        recordExecution(
            turn,
            CheckExecution(
                request = item.request,
                status = CheckStatus.DECLINED,
                declineComment = comment
            )
        )
    }

    private fun runCheck(turn: CheckTurn, item: CheckItem) {
        if (checkTurn !== turn || item !in turn.items || item.resolved || item.started) return
        item.started = true
        item.view?.setRunning()
        callbacks.setStatus("\uD83D\uDD27 Running: ${titleOf(item.request)}")
        executeAsync(item.request) completion@{ execution ->
            if (checkTurn !== turn || item.resolved) return@completion
            val headline = headlineOf(execution)
            item.resolved = true
            item.view?.setResult(
                headline,
                runCheckUseCase.formatForLlm(execution),
                execution.status == CheckStatus.PASSED
            )
            addSystemMessage(
                checkTurn?.sessionId ?: activeSessionId(),
                "\uD83D\uDD27 ${titleOf(item.request)} → $headline"
            )
            recordExecution(turn, execution)
            if (turn.sequential && checkTurn === turn) {
                if (execution.status == CheckStatus.PASSED) {
                    runNextQueued(turn)
                } else {
                    declineAllRemaining(turn, "skipped: previous check did not pass")
                }
            }
        }
    }

    private fun recordExecution(turn: CheckTurn, execution: CheckExecution) {
        if (checkTurn !== turn) return
        turn.executions.add(execution)
        val remaining = turn.items.size - turn.executions.size
        if (remaining > 0) {
            if (!turn.sequential) callbacks.setStatus("\uD83D\uDD27 $remaining check(s) awaiting approval")
            return
        }
        checkTurn = null
        val lineFeed = 10.toChar().toString()
        val formatted = turn.executions.joinToString(lineFeed + lineFeed) {
            runCheckUseCase.formatForLlm(it)
        }
        MaxVibesLogger.info(
            "Controller",
            "checkTurn complete",
            mapOf("mode" to turn.mode.name, "n" to turn.executions.size)
        )
        callbacks.setStatus("\uD83D\uDD27 Sending check results...")
        onBatchComplete(turn.sessionId, turn.mode, formatted)
    }

    private fun titleOf(request: CheckRequest): String =
        request.kind.name + (request.scope?.let { " · $it" } ?: "")

    private fun headlineOf(execution: CheckExecution): String {
        val seconds = execution.durationMs / 1000
        return when (execution.status) {
            CheckStatus.PASSED -> "\u2705 Passed · ${seconds}s"
            CheckStatus.FAILED -> {
                val failed = execution.testsFailed
                if (failed != null) {
                    "\u274C $failed/${execution.testsTotal ?: failed} tests failed · ${seconds}s"
                } else {
                    "\u274C ${execution.issues.size} error(s) · ${seconds}s"
                }
            }

            CheckStatus.TIMEOUT -> "\u23F1 Timeout after ${execution.request.timeoutSec}s"
            CheckStatus.ERROR -> "\u274C Failed to start"
            CheckStatus.DECLINED -> "\u2716 Declined"
            CheckStatus.UNSUPPORTED -> "\uD83D\uDEAB Not supported in this IDE"
        }
    }
}
