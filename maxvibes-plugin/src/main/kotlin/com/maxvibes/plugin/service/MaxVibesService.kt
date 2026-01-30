package com.maxvibes.plugin.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.maxvibes.adapter.llm.LangChainLLMService
import com.maxvibes.adapter.llm.LLMServiceFactory
import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import com.maxvibes.adapter.psi.PsiCodeRepository
import com.maxvibes.adapter.psi.context.PsiProjectContextProvider
import com.maxvibes.application.port.input.AnalyzeCodeUseCase
import com.maxvibes.application.port.input.ContextAwareModifyUseCase
import com.maxvibes.application.port.input.ModifyCodeUseCase
import com.maxvibes.application.port.output.*
import com.maxvibes.application.service.AnalyzeCodeService
import com.maxvibes.application.service.ContextAwareModifyService
import com.maxvibes.application.service.ModifyCodeService
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.context.ContextRequest
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.plugin.settings.MaxVibesSettings
import com.maxvibes.shared.result.Result

/**
 * Main service for MaxVibes plugin.
 * Manages dependencies and provides access to use cases.
 *
 * Automatically switches between real LLM and mock based on configuration.
 */
@Service(Service.Level.PROJECT)
class MaxVibesService(private val project: Project) {

    private val LOG = Logger.getInstance(MaxVibesService::class.java)

    // ========== Ports ==========

    val codeRepository: CodeRepository by lazy {
        PsiCodeRepository(project)
    }

    val projectContextProvider: ProjectContextPort by lazy {
        PsiProjectContextProvider(project)
    }

    /**
     * LLM Service - automatically selects real or mock based on settings.
     * Use refreshLLMService() after changing settings to recreate.
     */
    @Volatile
    private var _llmService: LLMService? = null

    val llmService: LLMService
        get() {
            if (_llmService == null) {
                _llmService = createLLMService()
            }
            return _llmService!!
        }

    val notificationService: IdeNotificationService by lazy {
        IdeNotificationService(project)
    }

    val notificationPort: NotificationPort
        get() = notificationService

    // ========== Use Cases ==========

    val modifyCodeUseCase: ModifyCodeUseCase by lazy {
        ModifyCodeService(codeRepository, llmService, notificationPort)
    }

    val analyzeCodeUseCase: AnalyzeCodeUseCase by lazy {
        AnalyzeCodeService(codeRepository, llmService, notificationPort)
    }

    val contextAwareModifyUseCase: ContextAwareModifyUseCase by lazy {
        ContextAwareModifyService(
            contextProvider = projectContextProvider,
            llmService = llmService,
            codeRepository = codeRepository,
            notificationPort = notificationPort
        )
    }

    // ========== LLM Service Creation ==========

    /**
     * Creates LLM service based on current settings.
     * Falls back to mock if not configured and fallback is enabled.
     */
    private fun createLLMService(): LLMService {
        val settings = MaxVibesSettings.getInstance()

        return try {
            if (settings.isConfigured) {
                LOG.info("Creating real LLM service: ${settings.provider} / ${settings.modelId}")
                createRealLLMService(settings)
            } else {
                handleNotConfigured(settings)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to create LLM service: ${e.message}", e)
            handleCreationError(settings, e)
        }
    }

    private fun createRealLLMService(settings: MaxVibesSettings): LLMService {
        val config = LLMProviderConfig(
            providerType = LLMProviderType.valueOf(settings.provider),
            apiKey = settings.currentApiKey,
            modelId = settings.modelId,
            baseUrl = if (settings.provider == "OLLAMA") settings.ollamaBaseUrl else null,
            temperature = settings.temperature
        )

        return LLMServiceFactory.create(config)
    }

    private fun handleNotConfigured(settings: MaxVibesSettings): LLMService {
        LOG.info("LLM not configured, checking environment variables...")

        // Try to create from environment variables
        return try {
            val envService = LLMServiceFactory.createFromEnvironment()
            LOG.info("Using LLM from environment variables: ${envService.getProviderInfo()}")
            envService
        } catch (e: Exception) {
            LOG.info("No environment variables found: ${e.message}")

            // Fall back to mock if enabled
            if (settings.enableMockFallback) {
                LOG.info("Using MockLLMService (mock fallback enabled)")
                MockLLMService()
            } else {
                // Return a service that always returns errors
                LOG.warn("No LLM configured and mock fallback disabled")
                NotConfiguredLLMService()
            }
        }
    }

    private fun handleCreationError(settings: MaxVibesSettings, e: Exception): LLMService {
        if (settings.enableMockFallback) {
            LOG.info("Falling back to MockLLMService due to error: ${e.message}")
            return MockLLMService()
        }
        return NotConfiguredLLMService()
    }

    // ========== Service Management ==========

    /**
     * Recreates the LLM service with current settings.
     * Call this after settings are changed.
     */
    fun refreshLLMService(): LLMService {
        _llmService = createLLMService()
        return _llmService!!
    }

    /**
     * Returns info about current LLM configuration
     */
    fun getLLMInfo(): String {
        return when (val service = llmService) {
            is LangChainLLMService -> service.getProviderInfo()
            is MockLLMService -> "Mock (testing mode)"
            is NotConfiguredLLMService -> "Not configured"
            else -> "Unknown"
        }
    }

    /**
     * Checks if real LLM is available
     */
    fun isRealLLMAvailable(): Boolean {
        return llmService is LangChainLLMService
    }

    companion object {
        fun getInstance(project: Project): MaxVibesService {
            return project.getService(MaxVibesService::class.java)
        }
    }
}

/**
 * LLM Service that always returns configuration error.
 * Used when no provider is configured and mock fallback is disabled.
 */
private class NotConfiguredLLMService : LLMService {

    private val configError = LLMError.ConfigurationError(
        "LLM is not configured. Please go to Settings → Tools → MaxVibes to configure an API key."
    )

    override suspend fun planContext(
        task: String,
        projectContext: ProjectContext
    ): Result<ContextRequest, LLMError> {
        return Result.Failure(configError)
    }

    override suspend fun generateModifications(
        task: String,
        gatheredContext: GatheredContext,
        projectContext: ProjectContext
    ): Result<List<Modification>, LLMError> {
        return Result.Failure(configError)
    }

    override suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): Result<List<Modification>, LLMError> {
        return Result.Failure(configError)
    }

    override suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): Result<AnalysisResponse, LLMError> {
        return Result.Failure(configError)
    }
}