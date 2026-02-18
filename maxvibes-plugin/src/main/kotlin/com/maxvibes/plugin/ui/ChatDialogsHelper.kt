package com.maxvibes.plugin.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.maxvibes.plugin.chat.ChatHistoryService
import java.awt.*
import java.io.File
import javax.swing.*

/**
 * Stateless helpers for dialogs: context files management and Claude instructions.
 */
object ChatDialogsHelper {

    /**
     * Shows the global context files management dialog.
     * @return New list of context files if confirmed, null if cancelled.
     */
    fun showContextFilesDialog(
        parent: JComponent,
        project: Project,
        chatHistory: ChatHistoryService
    ): List<String>? {
        val listModel = DefaultListModel<String>().apply {
            chatHistory.getGlobalContextFiles().forEach { addElement(it) }
        }
        val fileList = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }

        val panel = JPanel(BorderLayout(8, 8)).apply {
            preferredSize = Dimension(500, 350)
            border = JBUI.Borders.empty(8)

            add(JBLabel("<html><b>Global Context Files</b><br>" +
                    "<small>These files are included in every LLM request as background context.<br>" +
                    "Useful for architecture docs, coding guidelines, etc.</small></html>").apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
            }, BorderLayout.NORTH)

            add(JBScrollPane(fileList), BorderLayout.CENTER)

            val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                add(JButton("+ Add file").apply {
                    addActionListener {
                        val path = JOptionPane.showInputDialog(
                            parent,
                            "Relative path from project root\n(e.g. ARCHITECTURE.md, docs/guide.md):",
                            "Add Context File",
                            JOptionPane.PLAIN_MESSAGE
                        ) ?: return@addActionListener
                        val normalized = path.trim().replace('\\', '/')
                        if (normalized.isNotBlank() && !listModel.contains(normalized)) {
                            val basePath = project.basePath
                            val exists = basePath != null && File(basePath, normalized).exists()
                            if (exists) {
                                listModel.addElement(normalized)
                            } else {
                                val addAnyway = JOptionPane.showConfirmDialog(
                                    parent,
                                    "File '$normalized' not found in project.\nAdd anyway?",
                                    "File Not Found",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE
                                )
                                if (addAnyway == JOptionPane.YES_OPTION) {
                                    listModel.addElement(normalized)
                                }
                            }
                        }
                    }
                })
                add(JButton("- Remove").apply {
                    addActionListener {
                        val idx = fileList.selectedIndex
                        if (idx >= 0) listModel.remove(idx)
                    }
                })
                add(JButton("Browse...").apply {
                    addActionListener {
                        val basePath = project.basePath ?: return@addActionListener
                        val chooser = JFileChooser(basePath).apply {
                            fileSelectionMode = JFileChooser.FILES_ONLY
                            isMultiSelectionEnabled = true
                            dialogTitle = "Select context files"
                        }
                        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                            for (f in chooser.selectedFiles) {
                                val rel = f.toRelativeString(File(basePath)).replace('\\', '/')
                                if (!listModel.contains(rel)) {
                                    listModel.addElement(rel)
                                }
                            }
                        }
                    }
                })
            }
            add(buttonsPanel, BorderLayout.SOUTH)
        }

        val result = JOptionPane.showConfirmDialog(
            parent, panel, "Global Context Files",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        return if (result == JOptionPane.OK_OPTION) {
            (0 until listModel.size()).map { listModel.getElementAt(it) }
        } else null
    }

    /**
     * Shows the Claude instructions popup menu.
     */
    fun showClaudeInstructionsPopup(
        project: Project,
        anchorButton: JButton,
        onStatusUpdate: (String) -> Unit
    ) {
        val instrFile = File(project.basePath ?: return, ".maxvibes/claude-instructions.md")

        if (!instrFile.exists()) {
            instrFile.parentFile?.mkdirs()
            instrFile.writeText(DEFAULT_CLAUDE_INSTRUCTIONS)
        }

        val content = instrFile.readText()

        val popup = JPopupMenu().apply {
            add(JMenuItem("\uD83D\uDCCB Copy to clipboard").apply {
                font = font.deriveFont(12f)
                addActionListener {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(java.awt.datatransfer.StringSelection(content), null)
                    onStatusUpdate("Claude instructions copied!")
                }
            })
            add(JMenuItem("\u270F\uFE0F Edit in editor").apply {
                font = font.deriveFont(12f)
                addActionListener {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(instrFile)
                    if (vf != null) {
                        FileEditorManager.getInstance(project).openFile(vf, true)
                        onStatusUpdate("Claude instructions opened")
                    }
                }
            })
            add(JMenuItem("\uD83D\uDD04 Reset to default").apply {
                font = font.deriveFont(12f)
                addActionListener {
                    instrFile.writeText(DEFAULT_CLAUDE_INSTRUCTIONS)
                    onStatusUpdate("Claude instructions reset")
                }
            })
        }
        popup.show(anchorButton, 0, anchorButton.height)
    }
}

// ==================== Default Claude Instructions ====================

internal val DEFAULT_CLAUDE_INSTRUCTIONS = """
This project uses MaxVibes IDE plugin clipboard protocol for code modifications.

When you receive a JSON message containing "_protocol", "systemInstruction", "task", "fileTree", or "files" fields — this is a MaxVibes protocol message from the IDE plugin. Follow these rules STRICTLY:

1. RESPOND WITH ONLY A JSON OBJECT. Your entire response must be valid JSON — no markdown, no text before/after, no code blocks, no explanations outside the JSON.

2. DO NOT use computer tools, bash, file creation, artifacts, or any other tools. ALL code must go inside the JSON response in the "modifications" array.

3. Response format:
{
    "message": "Your explanation or answer",
    "requestedFiles": ["path/to/file.kt"],
    "reasoning": "why you need those files",
    "modifications": [...]
}

4. All fields are optional except "message" (always recommended):
   - "message" — your explanation or discussion
   - "requestedFiles" — files you need to see (triggers file gathering in IDE)
   - "reasoning" — why you need those files
   - "modifications" — code changes (see types below)

5. Modification types — PREFER element-level for existing files:

   | type             | When                          | path format                                              | content              | extra fields          |
   |------------------|-------------------------------|----------------------------------------------------------|----------------------|-----------------------|
   | REPLACE_ELEMENT  | Change a function/class/prop  | file:path/File.kt/class[Name]/function[method]           | Complete element     | elementKind           |
   | CREATE_ELEMENT   | Add new element to parent     | file:path/File.kt/class[Name]                            | New element code     | elementKind, position |
   | DELETE_ELEMENT    | Remove an element             | file:path/File.kt/class[Name]/function[old]              | (empty)              |                       |
   | ADD_IMPORT        | Add import to file            | file:path/File.kt                                        | (empty)              | importPath            |
   | REMOVE_IMPORT     | Remove import from file       | file:path/File.kt                                        | (empty)              | importPath            |
   | CREATE_FILE       | New file                      | src/main/kotlin/.../File.kt                              | Full file            |                       |
   | REPLACE_FILE      | Rewrite entire file           | src/main/kotlin/.../File.kt                              | Full file            |                       |

6. Element path format:
   file:src/main/kotlin/com/example/User.kt/class[User]/function[validate]
   Segments: class[Name], interface[Name], object[Name], function[Name], property[Name], companion_object, init, constructor[primary], enum_entry[Name]

7. Key rules for modifications:
   - PREFER REPLACE_ELEMENT/CREATE_ELEMENT over REPLACE_FILE — saves tokens!
   - Only use REPLACE_FILE when the majority of the file changes
   - For REPLACE_ELEMENT: content = COMPLETE element (annotations, modifiers, signature, body)
   - For CREATE_ELEMENT: set elementKind and position (LAST_CHILD, FIRST_CHILD)
   - Use ADD_IMPORT/REMOVE_IMPORT for imports — never manually edit the import block
   - "content" must be complete, compilable Kotlin code

8. For regular conversation (not MaxVibes protocol messages), respond normally as usual.
""".trimIndent()