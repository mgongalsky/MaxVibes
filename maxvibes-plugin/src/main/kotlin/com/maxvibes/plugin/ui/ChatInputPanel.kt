package com.maxvibes.plugin.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.plugin.voice.VoiceInputState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Image
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants

data class InputSubmission(
    val text: String,
    val planOnly: Boolean,
    val dryRun: Boolean,
    val addHistory: Boolean
)

/** Bottom chat input region. Owns widgets and delegates all decisions through callbacks. */
class ChatInputPanel(
    private val promptBar: JComponent,
    private val usageBar: JComponent,
    private val onSend: () -> Unit,
    private val onApprove: () -> Unit,
    private val onCopyJson: () -> Unit,
    private val onAttachTrace: () -> Unit,
    private val onClearTrace: () -> Unit,
    private val onAttachErrors: () -> Unit,
    private val onClearErrors: () -> Unit,
    private val onImagePasted: (AttachedImage) -> Unit,
    private val onClearImages: () -> Unit,
    private val onClearOneShot: () -> Unit
) : JPanel(BorderLayout(5, 4)) {
    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
    }
    private val sendButton = JButton("Send").apply {
        toolTipText = "Send message (Ctrl+Enter)"
    }
    private val voiceButton = JButton("🎙").apply {
        toolTipText = "Start voice input"
        preferredSize = Dimension(42, 26)
    }
    private var onVoiceToggle: () -> Unit = {}
    private var onAutoApproveToggle: (Boolean) -> Unit = {}
    private var isAutoApproveOn: () -> Boolean = { false }

    private val approveButton = JButton("\u2705 Approve").apply {
        toolTipText = "Approve & gather requested files (Claude Code)"
        isVisible = false
        foreground = JBColor(Color(0x3E2C00), Color(0x241C00))
        putClientProperty("JButton.backgroundColor", JBColor(Color(0xF5C518), Color(0xD4AC0D)))
        putClientProperty("JButton.borderColor", JBColor(Color(0xC9A227), Color(0x9A7D0A)))
    }
    private val autoApproveCheckbox = JBCheckBox("\uD83D\uDD13 Auto").apply {
        toolTipText = "Approve everything in this session automatically, until switched off"
    }
    private val dryRunCheckbox = JBCheckBox("Dry run").apply {
        toolTipText = "Show plan without applying changes"
    }
    private val planOnlyCheckbox = JBCheckBox("\uD83D\uDCAC Plan").apply {
        toolTipText = "Plan-only mode"
    }
    private val addHistoryCheckbox = JBCheckBox("Add History").apply {
        toolTipText = "Share gathered file list with LLM (use when starting a new LLM chat)"
        isVisible = false
    }
    private val copyJsonButton = JButton("\uD83D\uDCCB Copy JSON").apply {
        toolTipText = "Re-copy last generated JSON"
        isVisible = false
    }
    private val attachErrorsButton = JButton("\uD83D\uDC1E Errors").apply {
        toolTipText = "Attach IDE errors from open files"
        font = font.deriveFont(11f)
        preferredSize = Dimension(85, 26)
    }
    private val errorsIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0xD32F2F), Color(0xEF5350))
        font = font.deriveFont(Font.BOLD, 11f)
        isVisible = false
    }
    private val clearErrorsButton = JButton("\u2715").apply {
        toolTipText = "Remove attached errors"
        font = font.deriveFont(9f)
        preferredSize = Dimension(20, 20)
        isVisible = false
    }
    private val attachTraceButton = JButton("\uD83D\uDCCE Trace").apply {
        toolTipText = "Paste error/stacktrace/logs (Ctrl+Shift+V)"
        font = font.deriveFont(11f)
        preferredSize = Dimension(80, 26)
    }
    private val traceIndicator = JBLabel("").apply {
        foreground = JBColor(Color(0xFF9800), Color(0xFFB74D))
        font = font.deriveFont(Font.BOLD, 11f)
        isVisible = false
    }
    private val clearTraceButton = JButton("\u2715").apply {
        toolTipText = "Remove attached trace"
        font = font.deriveFont(9f)
        preferredSize = Dimension(20, 20)
        isVisible = false
    }
    private val oneShotChip = JBLabel("").apply {
        foreground = JBColor(Color(0x7B1FA2), Color(0xBA68C8))
        font = font.deriveFont(Font.BOLD, 11f)
        isVisible = false
    }
    private val clearOneShotButton = JButton("\u2715").apply {
        toolTipText = "Cancel one-shot skill"
        font = font.deriveFont(9f)
        preferredSize = Dimension(20, 20)
        isVisible = false
    }
    private val attachmentsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        background = JBColor.background()
        isVisible = false
    }
    private val traceBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        background = JBColor.background()
        border = JBUI.Borders.empty(2, 8, 0, 8)
        add(traceIndicator)
        add(clearTraceButton)
        add(errorsIndicator)
        add(clearErrorsButton)
        add(attachmentsPanel)
        isVisible = false
    }

    private var hasImages = false
    private var oneShotArmed = false
    private var attachmentsRequireBar = false

    init {
        border = JBUI.Borders.empty(4, 8, 8, 8)
        background = JBColor.background()
        attachmentsPanel.add(oneShotChip)
        attachmentsPanel.add(clearOneShotButton)
        add(traceBar, BorderLayout.NORTH)
        add(buildTextArea(), BorderLayout.CENTER)
        add(buildBottomRows(), BorderLayout.SOUTH)
        wireListeners()
    }

    private fun buildTextArea(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        add(JBScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(10, 96)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
    }

    private fun buildBottomRows(): JPanel = JPanel(BorderLayout()).apply {
        background = JBColor.background()
        add(promptBar, BorderLayout.NORTH)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = JBColor.background()
            add(attachErrorsButton)
            add(attachTraceButton)
            add(addHistoryCheckbox)
            add(planOnlyCheckbox)
            add(dryRunCheckbox)
            add(copyJsonButton)
            add(autoApproveCheckbox)
            add(approveButton)
            add(voiceButton)
            add(sendButton)
        }, BorderLayout.CENTER)
        add(usageBar, BorderLayout.SOUTH)
    }

    private fun wireListeners() {
        sendButton.addActionListener { onSend() }
        approveButton.addActionListener { onApprove() }
        copyJsonButton.addActionListener { onCopyJson() }
        attachTraceButton.addActionListener { onAttachTrace() }
        clearTraceButton.addActionListener { onClearTrace() }
        attachErrorsButton.addActionListener { onAttachErrors() }
        clearErrorsButton.addActionListener { onClearErrors() }
        clearOneShotButton.addActionListener { onClearOneShot() }
        voiceButton.addActionListener { onVoiceToggle() }

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    onSend()
                    e.consume()
                } else if (e.keyCode == KeyEvent.VK_V && e.isControlDown && e.isShiftDown) {
                    onAttachTrace()
                    e.consume()
                }
            }
        })

        runCatching { ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE) }
            .getOrNull()
            ?.let { pasteAction ->
                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) = pasteImageOrText()
                }.registerCustomShortcutSet(pasteAction.shortcutSet, inputArea)
            }

        inputArea.actionMap.put("maxvibes-paste", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = pasteImageOrText()
        })
        inputArea.inputMap.put(KeyStroke.getKeyStroke("ctrl V"), "maxvibes-paste")
        inputArea.inputMap.put(KeyStroke.getKeyStroke("shift INSERT"), "maxvibes-paste")
    }

    private fun pasteImageOrText() {
        val image = ImageAttachments.fromClipboard()
        if (image != null) onImagePasted(image) else inputArea.paste()
    }

    fun setVoiceToggleAction(action: () -> Unit) {
        onVoiceToggle = action
    }

    /** The checkbox keeps no state: [isOn] is re-read on every render, so it cannot show another session's policy. */
    fun setAutoApproveToggle(isOn: () -> Boolean, onToggle: (Boolean) -> Unit) {
        isAutoApproveOn = isOn
        onAutoApproveToggle = onToggle
        autoApproveCheckbox.isSelected = isOn()
        autoApproveCheckbox.addActionListener { onAutoApproveToggle(autoApproveCheckbox.isSelected) }
    }

    fun setVoiceState(state: VoiceInputState) {
        when (state) {
            VoiceInputState.IDLE -> {
                voiceButton.text = "🎙"
                voiceButton.toolTipText = "Start voice input"
                voiceButton.isEnabled = true
            }

            VoiceInputState.STARTING -> {
                voiceButton.text = "…"
                voiceButton.toolTipText = "Opening microphone"
                voiceButton.isEnabled = false
            }

            VoiceInputState.RECORDING -> {
                voiceButton.text = "■"
                voiceButton.toolTipText = "Stop and transcribe"
                voiceButton.isEnabled = true
            }

            VoiceInputState.TRANSCRIBING -> {
                voiceButton.text = "…"
                voiceButton.toolTipText = "Transcribing voice"
                voiceButton.isEnabled = false
            }
        }
    }

    fun insertTranscript(transcript: String) {
        val normalized = transcript.trim()
        if (normalized.isEmpty()) return
        val position = inputArea.caretPosition.coerceIn(0, inputArea.text.length)
        val prefix = if (position > 0 && !inputArea.text[position - 1].isWhitespace()) " " else ""
        val suffix = if (position < inputArea.text.length && !inputArea.text[position].isWhitespace()) " " else ""
        inputArea.insert(prefix + normalized + suffix, position)
        inputArea.caretPosition = position + prefix.length + normalized.length
        inputArea.requestFocusInWindow()
    }

    fun takeSubmission(): InputSubmission? {
        val text = inputArea.text.trim()
        if (text.isBlank()) return null
        val submission = InputSubmission(
            text = text,
            planOnly = planOnlyCheckbox.isSelected,
            dryRun = dryRunCheckbox.isSelected,
            addHistory = addHistoryCheckbox.isSelected
        )
        inputArea.text = ""
        addHistoryCheckbox.isSelected = false
        return submission
    }

    fun setText(text: String) {
        inputArea.text = text
    }

    fun prefill(text: String, append: Boolean) {
        if (append && inputArea.text.isNotBlank()) {
            inputArea.text = inputArea.text.trimEnd() + " " + text
        } else if (text.isNotBlank()) {
            inputArea.text = text
        }
        inputArea.caretPosition = inputArea.text.length
        inputArea.requestFocusInWindow()
    }

    fun setPlanOnly(enabled: Boolean) {
        planOnlyCheckbox.isSelected = enabled
    }

    fun applyModeDecision(decision: ModeUiDecision) {
        sendButton.text = decision.sendButtonText
        dryRunCheckbox.isVisible = decision.dryRunVisible
        copyJsonButton.isVisible = decision.copyJsonVisible
        addHistoryCheckbox.isVisible = decision.addHistoryVisible
    }

    fun applyApproveState(approveVisible: Boolean) {
        autoApproveCheckbox.isSelected = isAutoApproveOn()
        approveButton.isVisible = approveVisible
        if (approveVisible) {
            sendButton.isEnabled = false
            sendButton.toolTipText = "Press Approve to continue, or start a new chat (+ New)"
        } else {
            sendButton.toolTipText = "Send message (Ctrl+Enter)"
        }
    }

    fun updateIndicators(trace: String?, errors: String?) {
        val state = AttachmentIndicators.describe(trace, errors, hasImages)
        traceIndicator.isVisible = state.traceVisible
        clearTraceButton.isVisible = state.traceVisible
        state.traceText?.let { traceIndicator.text = it }
        errorsIndicator.isVisible = state.errorsVisible
        clearErrorsButton.isVisible = state.errorsVisible
        state.errorsText?.let { errorsIndicator.text = it }
        attachmentsRequireBar = state.barVisible
        refreshBar()
    }

    fun showImages(images: List<AttachedImage>) {
        hasImages = images.isNotEmpty()
        attachmentsPanel.removeAll()
        images.forEachIndexed { index, image -> attachmentsPanel.add(createThumbnail(image, index)) }
        attachmentsPanel.add(oneShotChip)
        attachmentsPanel.add(clearOneShotButton)
        refreshAttachments()
    }

    fun showOneShot(label: String?) {
        oneShotArmed = label != null
        oneShotChip.text = if (oneShotArmed) "\u26A1 $label (1\u00D7)" else ""
        oneShotChip.isVisible = oneShotArmed
        clearOneShotButton.isVisible = oneShotArmed
        refreshAttachments()
    }

    private fun refreshAttachments() {
        attachmentsPanel.isVisible = hasImages || oneShotArmed
        attachmentsPanel.revalidate()
        attachmentsPanel.repaint()
        refreshBar()
    }

    private fun refreshBar() {
        traceBar.isVisible = attachmentsRequireBar || oneShotArmed
        traceBar.revalidate()
        traceBar.repaint()
    }

    private fun createThumbnail(image: AttachedImage, index: Int): JComponent {
        val clearListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClearImages()
        }
        val previewIcon = runCatching {
            val bytes = java.util.Base64.getDecoder().decode(image.base64Data)
            val buffered = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                ?: return@runCatching null
            val size = ThumbnailScale.fit(buffered.width, buffered.height)
            ImageIcon(buffered.getScaledInstance(size.width, size.height, Image.SCALE_SMOOTH))
        }.getOrNull()

        return JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
            background = JBColor.background()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(2, 4)
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Attached image ${index + 1} \u2014 click to remove"
            val previewLabel = if (previewIcon != null) JBLabel(previewIcon) else JBLabel("\uD83D\uDDBC")
            val indexLabel = JBLabel("#${index + 1}").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(10f)
            }
            add(previewLabel)
            add(indexLabel)
            addMouseListener(clearListener)
            previewLabel.addMouseListener(clearListener)
            indexLabel.addMouseListener(clearListener)
        }
    }

    fun setControlsEnabled(enabled: Boolean) {
        listOf<JComponent>(
            inputArea, sendButton, approveButton, dryRunCheckbox, planOnlyCheckbox,
            addHistoryCheckbox, copyJsonButton, attachTraceButton, clearTraceButton,
            attachErrorsButton, clearErrorsButton, voiceButton
        ).forEach { it.isEnabled = enabled }
    }
}
