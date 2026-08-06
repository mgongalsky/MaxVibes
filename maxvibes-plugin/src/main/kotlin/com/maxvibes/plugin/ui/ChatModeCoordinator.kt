package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.settings.MaxVibesSettings
import javax.swing.JComponent
import javax.swing.JOptionPane

interface ChatModeDialogs {
    fun confirmClipboardReset(): Boolean
}

class SwingChatModeDialogs(
    private val parent: JComponent
) : ChatModeDialogs {
    override fun confirmClipboardReset(): Boolean =
        JOptionPane.showConfirmDialog(
            parent,
            "Active clipboard session will be reset. Continue?",
            "Switch Mode",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION
}

/** Coordinates mode switching, clipboard reset and mode-specific UI policy. */
class ChatModeCoordinator(
    private val modeState: InteractionModeState,
    private val dialogs: ChatModeDialogs,
    private val clipboardStatus: () -> ClipboardSessionStatus,
    private val activeSessionId: () -> String,
    private val resetClipboard: (String) -> Unit,
    private val forceActivate: (String) -> Unit,
    private val forceAwaitPaste: (String) -> Unit,
    private val onSelectMode: (InteractionMode) -> Unit,
    private val onApplyDecision: (ModeUiDecision) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onSystemMessage: (String) -> Unit,
    private val onRefresh: () -> Unit
) {

    val currentMode: InteractionMode get() = modeState.currentMode

    fun initialize() {
        modeState.syncFromSettings()
        onSelectMode(modeState.currentMode)
    }

    fun handleSelection(newMode: InteractionMode) {
        val previousMode = modeState.currentMode
        if (newMode == previousMode) return

        if (
            previousMode == InteractionMode.CLIPBOARD &&
            clipboardStatus() == ClipboardSessionStatus.AWAITING_PASTE
        ) {
            if (!dialogs.confirmClipboardReset()) {
                onSelectMode(previousMode)
                return
            }
            resetClipboard(activeSessionId())
        }

        MaxVibesLogger.info(
            "ChatPanel",
            "switchMode",
            mapOf("from" to previousMode.name, "to" to newMode.name)
        )
        modeState.switchMode(newMode)

        val label = MaxVibesSettings.INTERACTION_MODES
            .find { it.first == newMode.name }
            ?.second
            ?: newMode.name
        onStatus("Mode: $label")
        onSystemMessage("⚙️ Switched to $label")
    }

    fun applyUi(mode: InteractionMode, status: ClipboardSessionStatus) {
        onApplyDecision(ModeUiPolicy.decide(mode, status))
    }

    fun handleIndicatorAction(action: IndicatorAction) {
        val sessionId = activeSessionId()
        when (action) {
            IndicatorAction.FORCE_ACTIVATE -> forceActivate(sessionId)
            IndicatorAction.FORCE_AWAIT_PASTE -> forceAwaitPaste(sessionId)
        }
        onRefresh()
    }
}
