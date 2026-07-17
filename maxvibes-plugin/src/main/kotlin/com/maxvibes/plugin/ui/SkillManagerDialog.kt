package com.maxvibes.plugin.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.maxvibes.application.port.output.SpecificPromptRepository
import com.maxvibes.domain.model.interaction.PromptSource
import com.maxvibes.domain.model.interaction.SpecificPrompt
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Skill manager: lists all discovered skills (project, legacy, global), creates new
 * ones from a template, installs the bundled starter set and opens SKILL.md files
 * in the IDE editor. Editing happens in the editor — this dialog is a launcher,
 * not an editor.
 */
class SkillManagerDialog(
    private val project: Project,
    private val repository: SpecificPromptRepository
) : DialogWrapper(project, true) {

    private val listModel = DefaultListModel<SpecificPrompt>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        setCellRenderer { cellList, value, _, isSelected, _ ->
            val badge = when (value.source) {
                PromptSource.PROJECT_SKILL -> "[project]"
                PromptSource.LEGACY -> "[legacy]"
                PromptSource.GLOBAL_SKILL -> "[global, read-only]"
            }
            JBLabel("${value.name} $badge — ${value.description.ifBlank { "(no description)" }}").apply {
                border = JBUI.Borders.empty(4, 8)
                isOpaque = true
                background = if (isSelected) cellList.selectionBackground else cellList.background
                foreground = if (isSelected) cellList.selectionForeground else cellList.foreground
            }
        }
    }

    init {
        title = "Manage Skills"
        init()
        reload()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.preferredSize = Dimension(600, 340)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)

        val buttons = JPanel().apply {
            add(JButton("New Skill").apply { addActionListener { createSkill() } })
            add(JButton("Install Starter Skills").apply {
                toolTipText =
                    "Copy the bundled starter skills (Feathers recipes + quick utilities) into .claude/skills/. Existing skills are never overwritten."
                addActionListener { installStarterSkills() }
            })
            add(JButton("Open in Editor").apply { addActionListener { openSelected() } })
            add(JButton("Delete").apply { addActionListener { deleteSelected() } })
            add(JButton("Reload").apply { addActionListener { reload() } })
        }
        val hint = JBLabel(
            "Skills live in .claude/skills/<name>/SKILL.md. The catalog is loaded into Claude Code " +
                    "at session start — press Reset in the chat after changing skills."
        ).apply { font = font.deriveFont(11f) }

        panel.add(JPanel(BorderLayout()).apply {
            add(buttons, BorderLayout.WEST)
            add(hint, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        return panel
    }

    override fun createActions() = arrayOf(okAction)

    private fun reload() {
        listModel.clear()
        repository.loadAll().forEach { listModel.addElement(it) }
    }

    private fun createSkill() {
        val name = Messages.showInputDialog(
            project, "Skill name (directory-friendly, e.g. refactoring-safety):", "New Skill", null
        )?.trim()?.takeIf { it.isNotBlank() } ?: return
        val base = project.basePath ?: return
        val md = File(File(base, ".claude/skills/$name"), "SKILL.md")
        if (md.exists()) {
            Messages.showWarningDialog(project, "Skill '$name' already exists.", "New Skill")
            openFile(md)
            return
        }
        try {
            md.parentFile.mkdirs()
            md.writeText(
                "---\n" +
                        "name: $name\n" +
                        "description: One line describing when this skill should be used\n" +
                        "---\n\n" +
                        "# $name\n\n" +
                        "Step-by-step instructions the model must follow when this skill is active.\n",
                Charsets.UTF_8
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Failed to create skill: ${e.message}", "New Skill")
            return
        }
        reload()
        openFile(md)
    }

    /**
     * Copies bundled starter skills (plugin resources under /skills/<id>/SKILL.md)
     * into the project's .claude/skills/. Skills whose SKILL.md already exists are
     * skipped — user edits are never overwritten.
     */
    private fun installStarterSkills() {
        val base = project.basePath ?: return
        val root = File(base, ".claude/skills")
        var installed = 0
        var skipped = 0
        var failed = 0
        STARTER_SKILLS.forEach { id ->
            val dir = File(root, id)
            if (File(dir, "SKILL.md").exists()) {
                skipped++
                return@forEach
            }
            val res = javaClass.getResourceAsStream("/skills/$id/SKILL.md")
            if (res == null) {
                failed++
                return@forEach
            }
            try {
                dir.mkdirs()
                res.use { input -> File(dir, "SKILL.md").writeBytes(input.readBytes()) }
                installed++
            } catch (e: Exception) {
                failed++
            }
        }
        reload()
        val summary = buildString {
            append("Installed: $installed, already present: $skipped")
            if (failed > 0) append(", failed or missing resources: $failed")
        }
        Messages.showInfoMessage(project, summary, "Starter Skills")
    }

    private fun openSelected() {
        val path = list.selectedValue?.filePath ?: return
        openFile(File(path))
    }

    private fun deleteSelected() {
        val selected = list.selectedValue ?: return
        if (selected.source == PromptSource.GLOBAL_SKILL) {
            Messages.showInfoMessage(project, "Global skills are read-only here.", "Delete Skill")
            return
        }
        val path = selected.filePath ?: return
        val confirmed = Messages.showYesNoDialog(
            project, "Delete skill '${selected.name}'?", "Delete Skill", null
        ) == Messages.YES
        if (!confirmed) return
        try {
            val file = File(path)
            if (selected.source == PromptSource.PROJECT_SKILL) file.parentFile?.deleteRecursively() else file.delete()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Failed to delete: ${e.message}", "Delete Skill")
        }
        reload()
    }

    private fun openFile(file: File) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    companion object {
        /** Bundled starter skills shipped as plugin resources under /skills/<id>/SKILL.md. */
        private val STARTER_SKILLS = listOf(
            "feathers-characterize",
            "feathers-seam",
            "feathers-sprout",
            "feathers-extract-override",
            "explain-element",
            "find-smells",
            "write-kdoc",
            "write-unittest"
        )
    }
}
