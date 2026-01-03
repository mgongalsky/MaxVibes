package com.maxvibes.plugin.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.maxvibes.application.port.input.AnalyzeCodeRequest
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.plugin.service.MaxVibesService
import com.maxvibes.plugin.ui.AnalysisResultDialog
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile

class AnalyzeCodeAction : AnAction() {

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

        // Запрашиваем вопрос
        val question = Messages.showInputDialog(
            project,
            "What would you like to know about this code?",
            "MaxVibes - Analyze Code",
            Messages.getQuestionIcon()
        )

        if (question.isNullOrBlank()) return

        // Получаем путь к файлу
        val basePath = project.basePath ?: ""
        val filePath = psiFile.virtualFile?.path?.removePrefix(basePath)?.removePrefix("/")
            ?: psiFile.name
        val elementPath = ElementPath.file(filePath)

        // Запускаем в фоне
        val service = MaxVibesService.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "MaxVibes: Analyzing...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                service.notificationService.setProgressIndicator(indicator)

                runBlocking {
                    val request = AnalyzeCodeRequest(
                        question = question,
                        targetPath = elementPath
                    )

                    val response = service.analyzeCodeUseCase.execute(request)

                    // Показываем результат в диалоге
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        AnalysisResultDialog(project, response).show()
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = psiFile is KtFile
    }
}