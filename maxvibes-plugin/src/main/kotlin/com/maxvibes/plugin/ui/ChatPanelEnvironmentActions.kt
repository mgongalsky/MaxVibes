package com.maxvibes.plugin.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.service.PromptService
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent

/** IntelliJ and desktop actions that do not belong to ChatPanel rendering. */
class ChatPanelEnvironmentActions(
    private val project: Project,
    private val toolWindow: ToolWindow,
    private val parent: JComponent,
    private val chatTreeService: ChatTreeService,
    private val promptService: PromptService,
    private val claudeCodeLogPath: (String) -> String?,
    private val attachTrace: (String) -> Unit,
    private val onContextChanged: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onToolWindowState: (maximized: Boolean, floating: Boolean) -> Unit
) {

    fun attachTraceFromClipboard() {
        val content = try {
            Toolkit.getDefaultToolkit().systemClipboard
                .getData(DataFlavor.stringFlavor) as? String
        } catch (error: Exception) {
            onStatus("Clipboard error: ${error.message}")
            return
        }
        if (content.isNullOrBlank()) {
            onStatus("Clipboard is empty")
            return
        }
        attachTrace(content)
    }

    fun openPrompts() {
        promptService.openOrCreatePrompts()
        onStatus("Prompts opened")
    }

    fun showClaudeInstructions(anchor: JButton) {
        ChatDialogsHelper.showClaudeInstructionsPopup(project, anchor, onStatus)
    }

    fun showContextFilesDialog() {
        val result = ChatDialogsHelper.showContextFilesDialog(parent, project, chatTreeService)
            ?: return
        chatTreeService.setGlobalContextFiles(result)
        onContextChanged()
        onStatus("Context files: ${result.size}")
    }

    fun openClaudeCodeLog() {
        val path = claudeCodeLogPath(chatTreeService.getActiveSession().id)
        if (path == null) {
            onStatus("No Claude Code log for this dialog yet — send a message first")
            return
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path))
        if (file == null) {
            onStatus("Log file not found: $path")
            return
        }
        file.refresh(false, false)
        FileEditorManager.getInstance(project).openFile(file, true)
        onStatus("Opened Claude Code log")
    }

    fun openPlanDoc(docPath: String) {
        val basePath = project.basePath ?: return
        val file = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(File(basePath, docPath))
        if (file == null) {
            onStatus("Doc not found: $docPath")
            return
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        onStatus("Opened $docPath")
    }

    fun toggleMaximize() {
        val manager = ToolWindowManager.getInstance(project)
        manager.setMaximized(toolWindow, !manager.isMaximized(toolWindow))
        refreshToolWindowState()
    }

    fun toggleWindowed() {
        val floating = toolWindow.type == ToolWindowType.FLOATING ||
                toolWindow.type == ToolWindowType.WINDOWED
        toolWindow.setType(
            if (floating) ToolWindowType.DOCKED else ToolWindowType.FLOATING,
            null
        )
        refreshToolWindowState()
    }

    fun refreshToolWindowState() {
        val manager = ToolWindowManager.getInstance(project)
        val floating = toolWindow.type == ToolWindowType.FLOATING ||
                toolWindow.type == ToolWindowType.WINDOWED
        onToolWindowState(manager.isMaximized(toolWindow), floating)
    }

    fun setCommitMessage(message: String) {
        val commitMessage = message.trim()
        if (commitMessage.isBlank()) {
            onStatus("Generated commit message is empty")
            return
        }

        try {
            VcsConfiguration.getInstance(project).saveCommitMessage(commitMessage)

            // COMMIT_MESSAGE_CONTROL is published by a data provider that sits inside the commit
            // panel, and getDataContext only collects data from the component and its ancestors —
            // asking the content root or the frame never reaches it, so the subtree is searched.
            fun inject(component: java.awt.Component?): Boolean {
                if (component == null) return false
                val control = DataManager.getInstance()
                    .getDataContext(component)
                    .getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
                if (control != null) {
                    control.setCommitMessage(commitMessage)
                    return true
                }
                if (component is java.awt.Container) {
                    for (child in component.components) {
                        if (inject(child)) return true
                    }
                }
                return false
            }

            fun commitToolWindow(): com.intellij.openapi.wm.ToolWindow? {
                val manager = ToolWindowManager.getInstance(project)
                return manager.getToolWindow("Commit") ?: manager.getToolWindow("Version Control")
            }

            fun tryVisibleContexts(): Boolean {
                val focusOwner = java.awt.KeyboardFocusManager
                    .getCurrentKeyboardFocusManager()
                    .focusOwner
                if (inject(focusOwner)) return true

                val contentManager = commitToolWindow()?.contentManager
                if (inject(contentManager?.selectedContent?.component)) return true
                contentManager?.contents?.forEach { content ->
                    if (inject(content.component)) return true
                }

                val frame = com.intellij.openapi.wm.WindowManager
                    .getInstance()
                    .getFrame(project)
                return inject(frame)
            }

            javax.swing.SwingUtilities.invokeLater {
                try {
                    if (tryVisibleContexts()) {
                        MaxVibesLogger.info(
                            "ChatPanel",
                            "setCommitMessage: inserted into active commit UI",
                            mapOf("len" to commitMessage.length)
                        )
                        onStatus("Commit message inserted")
                        return@invokeLater
                    }

                    val commitWindow = commitToolWindow()
                    if (commitWindow == null) {
                        MaxVibesLogger.info(
                            "ChatPanel",
                            "setCommitMessage: Commit tool window unavailable; saved to VCS history",
                            mapOf("len" to commitMessage.length)
                        )
                        onStatus("Commit message saved — open Commit to use it")
                        return@invokeLater
                    }

                    commitWindow.activate({
                        javax.swing.SwingUtilities.invokeLater {
                            try {
                                if (tryVisibleContexts()) {
                                    MaxVibesLogger.info(
                                        "ChatPanel",
                                        "setCommitMessage: inserted after activating Commit",
                                        mapOf("len" to commitMessage.length)
                                    )
                                    onStatus("Commit message inserted")
                                } else {
                                    MaxVibesLogger.warn(
                                        "ChatPanel",
                                        "setCommitMessage: commit control unavailable after activation",
                                        data = mapOf("len" to commitMessage.length)
                                    )
                                    onStatus("Commit message saved, but the commit field was not found")
                                }
                            } catch (error: Exception) {
                                MaxVibesLogger.error(
                                    "ChatPanel",
                                    "setCommitMessage retry failed",
                                    error
                                )
                                onStatus("Commit message saved; insertion failed: ${error.message}")
                            }
                        }
                    }, true)
                } catch (error: Exception) {
                    MaxVibesLogger.error("ChatPanel", "setCommitMessage failed", error)
                    onStatus("Commit message saved; insertion failed: ${error.message}")
                }
            }
        } catch (error: Exception) {
            MaxVibesLogger.error("ChatPanel", "setCommitMessage failed", error)
            onStatus("Failed to save commit message: ${error.message}")
        }
    }
}
