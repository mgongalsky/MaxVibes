package com.maxvibes.plugin.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.maxvibes.application.port.input.ModifyCodeRequest
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.plugin.service.MaxVibesService
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile

class ModifyCodeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        if (psiFile !is KtFile) {
            Messages.showWarningDialog(
                project,
                "MaxVibes currently supports only Kotlin files",
                "MaxVibes"
            )
            return
        }

        // Запрашиваем инструкцию
        val instruction = Messages.showInputDialog(
            project,
            "What would you like to do with this code?",
            "MaxVibes - Modify Code",
            Messages.getQuestionIcon()
        )

        if (instruction.isNullOrBlank()) return

        // Получаем путь к файлу (в read action)
        val elementPath = runReadAction {
            val basePath = project.basePath ?: ""
            val filePath = psiFile.virtualFile?.path?.removePrefix(basePath)?.removePrefix("/")
                ?: psiFile.name
            ElementPath.file(filePath)
        }

        // Запускаем в фоне
        val service = MaxVibesService.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "MaxVibes: Processing...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)

                runBlocking {
                    val request = ModifyCodeRequest(
                        instruction = instruction,
                        targetPath = elementPath
                    )

                    val response = service.modifyCodeUseCase.execute(request)

                    if (!response.success) {
                        service.notificationPort.showError(response.summary)
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val psiFile = runReadAction {
            e.getData(CommonDataKeys.PSI_FILE)
        }
        e.presentation.isEnabledAndVisible = psiFile is KtFile
    }
}