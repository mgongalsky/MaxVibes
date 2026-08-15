package com.maxvibes.plugin.settings

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maxvibes.application.service.approval.ApprovalPolicyEditor
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalMode
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel

/**
 * Approval policy page: one choice per kind of action the agent can take.
 *
 * Deliberately logic-free — every choice goes straight into [ApprovalPolicyEditor],
 * which owns the draft/saved distinction and is covered by unit tests.
 */
class AutonomySettingsPanel(private val editor: ApprovalPolicyEditor) {

    private val combos: Map<AgentActionKind, JComboBox<ApprovalMode>> =
        AgentActionKind.values().associateWith { kind -> modeCombo(kind) }

    val panel: JPanel = buildPanel()

    /** Pulls the editor state into the widgets. Called from `Configurable.reset`. */
    fun refresh() {
        combos.forEach { (kind, combo) -> combo.selectedItem = editor.modeFor(kind) }
    }

    private fun modeCombo(kind: AgentActionKind): JComboBox<ApprovalMode> {
        val combo = JComboBox(ApprovalMode.values())
        combo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component = super.getListCellRendererComponent(
                list,
                modeLabel(value),
                index,
                isSelected,
                cellHasFocus
            )
        }
        combo.addActionListener {
            val selected = combo.selectedItem as? ApprovalMode ?: return@addActionListener
            editor.select(kind, selected)
        }
        return combo
    }

    private fun buildPanel(): JPanel {
        var builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>Approvals</b></html>"))
            .addComponent(
                JBLabel(
                    "<html>Choose what the agent may do on its own after you send a message.<br>" +
                            "Automatic steps are limited by the autonomy budget of a single turn.</html>"
                )
            )
        combos.forEach { (kind, combo) ->
            builder = builder.addLabeledComponent(JBLabel(kindLabel(kind)), combo)
        }
        return builder
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(8) }
    }

    private fun kindLabel(kind: AgentActionKind): String = when (kind) {
        AgentActionKind.VIEW_REQUEST -> "Requests for code:"
        AgentActionKind.MODIFICATION -> "Code modifications:"
        AgentActionKind.COMMAND -> "Terminal commands:"
        AgentActionKind.BUILD -> "Project builds:"
        AgentActionKind.TESTS -> "Test runs:"
        AgentActionKind.CONTINUATION -> "Continuing the work:"
    }

    private fun modeLabel(value: Any?): String = when (value) {
        ApprovalMode.ASK -> "Ask every time"
        ApprovalMode.AUTO_ALLOW -> "Allow automatically"
        else -> ""
    }
}
