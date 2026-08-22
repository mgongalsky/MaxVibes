package com.maxvibes.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.plugin.settings.ApprovalPolicySettings
import com.maxvibes.plugin.voice.VoiceInputState
import java.awt.*
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.*
import javax.swing.*
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.project.DumbAwareAction

data class InputSubmission(val text: String, val planOnly: Boolean, val dryRun: Boolean, val addHistory: Boolean)

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
    private val onClearOneShot: () -> Unit,
    private val onAttachText: (String) -> Unit = {}
) : JPanel(BorderLayout(5, 4)) {
    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
    }
    private val sendButton = JButton("Send")
    private val approveButton = JButton("\u2705 Approve").apply { isVisible = false }
    private val copyJsonButton = JButton("\uD83D\uDCCB Copy JSON").apply { isVisible = false }
    private val attachButton = JButton("\uD83D\uDCCE Clipboard").apply {
        toolTipText = "Attach clipboard text (Ctrl+Shift+V)"
    }
    private val errorsButton = JButton("\uD83D\uDC1E Errors")
    private val voiceButton = JButton("\uD83C\uDF99")
    private val clearTextButton = JButton("\u2715").apply { isVisible = false }
    private val clearErrorsButton = JButton("\u2715").apply { isVisible = false }
    private val textChip = JBLabel().apply {
        foreground = JBColor(Color(0xFF9800), Color(0xFFB74D))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
    }
    private val errorsChip = JBLabel().apply { isVisible = false }
    private val imageChip = JBLabel().apply { isVisible = false }
    private val oneShotChip = JBLabel().apply { isVisible = false }
    private val attachmentBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { isVisible = false }
    private val planOnly = JBCheckBox("\uD83D\uDCAC Plan")
    private val dryRun = JBCheckBox("Dry run")
    private val addHistory = JBCheckBox("Add History").apply { isVisible = false }
    private val autoApprove = JBCheckBox("\uD83D\uDD13 Auto")
    private val iterations = JSpinner(
        SpinnerNumberModel(
            ApprovalPolicySettings.DEFAULT_AUTONOMOUS_ITERATIONS,
            ApprovalPolicySettings.MIN_AUTONOMOUS_ITERATIONS,
            ApprovalPolicySettings.MAX_AUTONOMOUS_ITERATIONS,
            1
        )
    )
    private var onVoiceToggle: () -> Unit = {}
    private var attachedText: String? = null
    private var hasImages = false
    private var hasOneShot = false
    private var suppressAutoAttach = false
    private val pasteRouter = ClipboardPasteRouter()

    init {
        border = JBUI.Borders.empty(4, 8, 8, 8)
        installPasteInterception()
        listOf(
            textChip,
            clearTextButton,
            errorsChip,
            clearErrorsButton,
            imageChip,
            oneShotChip
        ).forEach(attachmentBar::add)
        add(attachmentBar, BorderLayout.NORTH)
        add(JBScrollPane(inputArea).apply { preferredSize = Dimension(10, 96) }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(promptBar, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(errorsButton); add(attachButton); add(addHistory); add(planOnly); add(dryRun); add(copyJsonButton)
                add(JBLabel("Iters")); add(iterations); add(autoApprove); add(approveButton); add(voiceButton); add(
                sendButton
            )
            }, BorderLayout.CENTER)
            add(usageBar, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        sendButton.addActionListener { onSend() }; approveButton.addActionListener { onApprove() }
        copyJsonButton.addActionListener { onCopyJson() }; attachButton.addActionListener { onAttachTrace() }
        errorsButton.addActionListener { onAttachErrors() }; clearTextButton.addActionListener { onClearTrace() }
        clearErrorsButton.addActionListener { onClearErrors() }; voiceButton.addActionListener { onVoiceToggle() }
        textChip.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                attachedText?.let { TextClipboardAttachments.showPreview(this@ChatInputPanel, it) }
            }
        })
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    onSend(); e.consume()
                } else if (e.keyCode == KeyEvent.VK_V && e.isControlDown && e.isShiftDown) {
                    onAttachTrace(); e.consume()
                }
            }
        })
    }

    fun setVoiceToggleAction(action: () -> Unit) {
        onVoiceToggle = action
    }

    fun setAutoApproveToggle(isOn: () -> Boolean, onToggle: (Boolean) -> Unit) {
        autoApprove.isSelected = isOn(); autoApprove.addActionListener { onToggle(autoApprove.isSelected) }
    }

    fun setAutonomyLimit(current: () -> Int, onChange: (Int) -> Unit) {
        iterations.value = current(); iterations.addChangeListener { onChange(iterations.value as Int) }
    }

    fun setVoiceState(state: VoiceInputState) {
        voiceButton.text = when (state) {
            VoiceInputState.IDLE -> "\uD83C\uDF99"; VoiceInputState.RECORDING -> "\u25A0"; else -> "\u2026"
        }; voiceButton.isEnabled = state == VoiceInputState.IDLE || state == VoiceInputState.RECORDING
    }

    fun insertTranscript(transcript: String) {
        if (transcript.isNotBlank()) prefill(transcript.trim(), true)
    }

    fun takeSubmission(): InputSubmission? {
        val text = inputArea.text.trim()
        if (text.isBlank()) return null
        return InputSubmission(
            text,
            planOnly.isSelected,
            dryRun.isSelected,
            addHistory.isSelected
        ).also {
            withoutAutoAttach { inputArea.text = "" }
            addHistory.isSelected = false
        }
    }

    fun setText(text: String) {
        withoutAutoAttach { inputArea.text = text }
    }

    fun prefill(text: String, append: Boolean) {
        withoutAutoAttach {
            inputArea.text =
                if (append && inputArea.text.isNotBlank()) inputArea.text.trimEnd() + " " + text else text
        }
        inputArea.caretPosition = inputArea.text.length
        inputArea.requestFocusInWindow()
    }

    fun setPlanOnly(enabled: Boolean) {
        planOnly.isSelected = enabled
    }

    fun applyModeDecision(decision: ModeUiDecision) {
        sendButton.text = decision.sendButtonText; dryRun.isVisible = decision.dryRunVisible; copyJsonButton.isVisible =
            decision.copyJsonVisible; addHistory.isVisible = decision.addHistoryVisible
    }

    fun applyApproveState(approveVisible: Boolean) {
        approveButton.isVisible = approveVisible; sendButton.isEnabled = !approveVisible
    }

    fun updateIndicators(trace: String?, errors: String?) {
        attachedText = trace
        val state = AttachmentIndicators.describe(trace, errors, hasImages); textChip.isVisible =
            state.traceVisible; clearTextButton.isVisible = state.traceVisible; state.traceText?.let {
            textChip.text = it
        }; errorsChip.isVisible = state.errorsVisible; clearErrorsButton.isVisible =
            state.errorsVisible; state.errorsText?.let { errorsChip.text = it }; refreshBar()
    }

    fun showImages(images: List<AttachedImage>) {
        val thumbnailMarker = "maxvibes.image.thumbnail"
        attachmentBar.components
            .filter { component ->
                (component as? JComponent)?.getClientProperty(thumbnailMarker) == true
            }
            .forEach(attachmentBar::remove)

        hasImages = images.isNotEmpty()
        imageChip.isVisible = false

        images.forEachIndexed { index, image ->
            val thumbnail = ImageAttachments.thumbnail(image)
            val label = JBLabel(thumbnail).apply {
                putClientProperty(thumbnailMarker, true)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Open image ${index + 1}"
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.border(), 1),
                    JBUI.Borders.empty(2)
                )
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        val preview = ImageAttachments.thumbnail(image, 1200, 800)
                        if (preview != null) {
                            JOptionPane.showMessageDialog(
                                this@ChatInputPanel,
                                JBLabel(preview),
                                "Attached image ${index + 1}",
                                JOptionPane.PLAIN_MESSAGE
                            )
                        }
                    }
                })
            }
            attachmentBar.add(label)
        }

        if (images.isNotEmpty()) {
            attachmentBar.add(JButton("\u2715").apply {
                putClientProperty(thumbnailMarker, true)
                toolTipText = "Remove all attached images"
                margin = JBUI.insets(2)
                addActionListener { onClearImages() }
            })
        }

        refreshBar()
        attachmentBar.revalidate()
        attachmentBar.repaint()
    }

    fun showOneShot(label: String?) {
        hasOneShot = label != null; oneShotChip.text =
            label?.let { "\u26A1 $it (1\u00D7)" }.orEmpty(); oneShotChip.isVisible =
            hasOneShot; refreshBar()
    }

    private fun refreshBar() {
        attachmentBar.isVisible =
            textChip.isVisible || errorsChip.isVisible || hasImages || hasOneShot; attachmentBar.revalidate(); attachmentBar.repaint()
    }

    fun setControlsEnabled(enabled: Boolean) {
        listOf(
            inputArea,
            sendButton,
            approveButton,
            copyJsonButton,
            attachButton,
            errorsButton,
            voiceButton,
            planOnly,
            dryRun,
            addHistory,
            autoApprove,
            iterations
        ).forEach { it.isEnabled = enabled }
    }

    private fun installPasteInterception() {
        inputArea.transferHandler?.let { inputArea.transferHandler = InterceptingTransferHandler(it) }
        (inputArea.document as? AbstractDocument)?.documentFilter = InterceptingDocumentFilter()

        val pasteActionKey = javax.swing.text.DefaultEditorKit.pasteAction
        val defaultPasteAction = inputArea.actionMap.get(pasteActionKey)
        val interceptingPaste = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                val transferable = try {
                    Toolkit.getDefaultToolkit().systemClipboard.getContents(inputArea)
                } catch (_: Exception) {
                    null
                }
                if (transferable == null || !captureTransfer(transferable)) {
                    defaultPasteAction?.actionPerformed(event)
                }
            }
        }
        inputArea.actionMap.put(pasteActionKey, interceptingPaste)

        // The IDE keymap owns Ctrl+V: $Paste is a TextComponentEditorAction, so it handles this
        // JBTextArea itself, pastes the string flavor only and consumes the event before Swing
        // sees it. A component-scoped action outranks the global keymap entry.
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                interceptingPaste.actionPerformed(
                    ActionEvent(inputArea, ActionEvent.ACTION_PERFORMED, pasteActionKey)
                )
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK), null)
            ),
            inputArea
        )
    }

    private fun withoutAutoAttach(block: () -> Unit) {
        suppressAutoAttach = true
        try {
            block()
        } finally {
            suppressAutoAttach = false
        }
    }

    private fun captureLargeText(text: String?, defer: Boolean): Boolean {
        if (suppressAutoAttach || text == null || !TextClipboardAttachments.shouldAutoAttach(text)) return false
        if (defer) SwingUtilities.invokeLater { onAttachText(text) } else onAttachText(text)
        return true
    }

    private fun captureTransfer(transferable: Transferable): Boolean =
        when (val route = pasteRouter.route(transferable, autoAttachText = !suppressAutoAttach)) {
            is PasteRoute.AttachImage -> {
                onImagePasted(route.image)
                true
            }

            is PasteRoute.AttachText -> {
                onAttachText(route.text)
                true
            }

            PasteRoute.PassThrough -> false
        }

    private inner class InterceptingTransferHandler(private val delegate: TransferHandler) : TransferHandler() {
        override fun canImport(support: TransferSupport): Boolean = delegate.canImport(support)

        override fun canImport(comp: JComponent, transferFlavors: Array<out DataFlavor>): Boolean =
            delegate.canImport(comp, transferFlavors)

        override fun importData(support: TransferSupport): Boolean =
            captureTransfer(support.transferable) || delegate.importData(support)

        override fun importData(comp: JComponent, t: Transferable): Boolean =
            captureTransfer(t) || delegate.importData(comp, t)

        override fun getSourceActions(c: JComponent): Int = delegate.getSourceActions(c)

        override fun exportToClipboard(comp: JComponent, clip: Clipboard, action: Int) =
            delegate.exportToClipboard(comp, clip, action)

        override fun exportAsDrag(comp: JComponent, e: InputEvent, action: Int) =
            delegate.exportAsDrag(comp, e, action)
    }

    private inner class InterceptingDocumentFilter : DocumentFilter() {
        override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
            if (captureLargeText(string, true)) return
            super.insertString(fb, offset, string, attr)
        }

        override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
            if (captureLargeText(text, true)) return
            super.replace(fb, offset, length, text, attrs)
        }
    }
}
