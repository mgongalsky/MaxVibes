package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode

/** Click behaviour offered by the mode indicator. */
enum class IndicatorAction { FORCE_ACTIVATE, FORCE_AWAIT_PASTE }

/**
 * Cursor, tooltip and click behaviour of the mode indicator.
 * A null [ModeUiDecision.indicatorDecoration] means the widget keeps whatever it had,
 * which is not the same as a decoration with a null [tooltip] (that clears it).
 */
data class IndicatorDecoration(
    val handCursor: Boolean,
    val tooltip: String?,
    val action: IndicatorAction?
)

/**
 * Widget state for one (mode, status) pair. A null [indicatorText] or
 * [indicatorDecoration] means "leave the widget untouched".
 */
data class ModeUiDecision(
    val sendButtonText: String,
    val indicatorVisible: Boolean,
    val indicatorText: String?,
    val ccLogLinkVisible: Boolean,
    val dryRunVisible: Boolean,
    val copyJsonVisible: Boolean,
    val addHistoryVisible: Boolean,
    val indicatorDecoration: IndicatorDecoration?
)

/** Maps interaction mode and clipboard status to mode-specific chat controls. */
object ModeUiPolicy {

    fun decide(mode: InteractionMode, status: ClipboardSessionStatus): ModeUiDecision {
        val ccLogLinkVisible = mode == InteractionMode.CLAUDE_CODE
        return when (mode) {
            InteractionMode.API -> ModeUiDecision(
                sendButtonText = "Send",
                indicatorVisible = false,
                indicatorText = null,
                ccLogLinkVisible = ccLogLinkVisible,
                dryRunVisible = true,
                copyJsonVisible = false,
                addHistoryVisible = false,
                indicatorDecoration = null
            )

            InteractionMode.CHEAP_API -> ModeUiDecision(
                sendButtonText = "Send",
                indicatorVisible = true,
                indicatorText = "\uD83D\uDCB0",
                ccLogLinkVisible = ccLogLinkVisible,
                dryRunVisible = true,
                copyJsonVisible = false,
                addHistoryVisible = false,
                indicatorDecoration = null
            )

            InteractionMode.CLIPBOARD -> clipboard(status, ccLogLinkVisible)

            InteractionMode.CLAUDE_CODE -> claudeCode(status, ccLogLinkVisible)
        }
    }

    private fun clipboard(status: ClipboardSessionStatus, ccLogLinkVisible: Boolean): ModeUiDecision = when (status) {
        ClipboardSessionStatus.AWAITING_PASTE -> ModeUiDecision(
            sendButtonText = "Paste",
            indicatorVisible = true,
            indicatorText = "\u23F3 Paste response",
            ccLogLinkVisible = ccLogLinkVisible,
            dryRunVisible = false,
            copyJsonVisible = true,
            addHistoryVisible = true,
            indicatorDecoration = IndicatorDecoration(
                handCursor = true,
                tooltip = "Click to skip paste and continue dialog",
                action = IndicatorAction.FORCE_ACTIVATE
            )
        )

        ClipboardSessionStatus.SESSION_ACTIVE -> ModeUiDecision(
            sendButtonText = "Send / Paste",
            indicatorVisible = true,
            indicatorText = "\uD83D\uDCCB Active",
            ccLogLinkVisible = ccLogLinkVisible,
            dryRunVisible = false,
            copyJsonVisible = false,
            addHistoryVisible = true,
            indicatorDecoration = IndicatorDecoration(
                handCursor = true,
                tooltip = "Click to go back to paste mode",
                action = IndicatorAction.FORCE_AWAIT_PASTE
            )
        )

        // AWAITING_APPROVE is Claude Code-only; clipboard falls back to IDLE visuals.
        ClipboardSessionStatus.IDLE,
        ClipboardSessionStatus.AWAITING_APPROVE -> ModeUiDecision(
            sendButtonText = "Generate",
            indicatorVisible = true,
            indicatorText = "\uD83D\uDCCB",
            ccLogLinkVisible = ccLogLinkVisible,
            dryRunVisible = false,
            copyJsonVisible = false,
            addHistoryVisible = true,
            indicatorDecoration = IndicatorDecoration(handCursor = false, tooltip = null, action = null)
        )
    }

    private fun claudeCode(status: ClipboardSessionStatus, ccLogLinkVisible: Boolean): ModeUiDecision = ModeUiDecision(
        sendButtonText = "Send",
        indicatorVisible = true,
        indicatorText = when (status) {
            ClipboardSessionStatus.AWAITING_APPROVE -> "\uD83E\uDD16 Awaiting Approve"
            ClipboardSessionStatus.SESSION_ACTIVE -> "\uD83E\uDD16 Active"
            ClipboardSessionStatus.IDLE,
            ClipboardSessionStatus.AWAITING_PASTE -> "\uD83E\uDD16 Claude Code"
        },
        ccLogLinkVisible = ccLogLinkVisible,
        dryRunVisible = false,
        copyJsonVisible = false,
        addHistoryVisible = false,
        indicatorDecoration = IndicatorDecoration(handCursor = false, tooltip = null, action = null)
    )
}
