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
        try {
            VcsConfiguration.getInstance(project).saveCommitMessage(message)

            fun tryInject(component: java.awt.Component): Boolean {
                val dataContext = DataManager.getInstance().getDataContext(component)
                val control = dataContext.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return false
                return try {
                    control.javaClass
                        .getMethod("setCommitMessage", String::class.java)
                        .invoke(control, message)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
            if (frame != null && tryInject(frame)) {
                MaxVibesLogger.info(
                    "ChatPanel",
                    "setCommitMessage: injected via frame",
                    mapOf("len" to message.length)
                )
                return
            }

            val commitWindow = ToolWindowManager.getInstance(project).getToolWindow("Commit")
            val component = commitWindow
                ?.takeIf { it.isVisible }
                ?.contentManager
                ?.selectedContent
                ?.component
            if (component != null && tryInject(component)) {
                MaxVibesLogger.info(
                    "ChatPanel",
                    "setCommitMessage: injected via Commit tool window",
                    mapOf("len" to message.length)
                )
                return
            }

            MaxVibesLogger.info(
                "ChatPanel",
                "setCommitMessage: saved to VCS history (commit UI not open)",
                mapOf("len" to message.length)
            )
        } catch (error: Exception) {
            MaxVibesLogger.error("ChatPanel", "setCommitMessage failed", error)
        }
    }
}
