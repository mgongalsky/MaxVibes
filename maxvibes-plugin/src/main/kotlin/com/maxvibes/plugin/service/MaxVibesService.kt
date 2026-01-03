package com.maxvibes.plugin.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.maxvibes.adapter.psi.PsiCodeRepository
import com.maxvibes.application.port.input.AnalyzeCodeUseCase
import com.maxvibes.application.port.input.ModifyCodeUseCase
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.LLMService
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.application.service.AnalyzeCodeService
import com.maxvibes.application.service.ModifyCodeService

@Service(Service.Level.PROJECT)
class MaxVibesService(private val project: Project) {

    // Порты
    val codeRepository: CodeRepository by lazy {
        PsiCodeRepository(project)
    }

    val llmService: LLMService by lazy {
        MockLLMService()
    }

    val notificationService: IdeNotificationService by lazy {
        IdeNotificationService(project)
    }

    val notificationPort: NotificationPort
        get() = notificationService

    // Use Cases
    val modifyCodeUseCase: ModifyCodeUseCase by lazy {
        ModifyCodeService(codeRepository, llmService, notificationPort)
    }

    val analyzeCodeUseCase: AnalyzeCodeUseCase by lazy {
        AnalyzeCodeService(codeRepository, llmService, notificationPort)
    }

    companion object {
        fun getInstance(project: Project): MaxVibesService {
            return project.getService(MaxVibesService::class.java)
        }
    }
}