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
import com.maxvibes.application.service.ClipboardInteractionService
import com.maxvibes.application.service.ContextAwareModifyService
import com.maxvibes.application.service.ModifyCodeService
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.context.ContextRequest
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.plugin.clipboard.ClipboardAdapter
import com.maxvibes.plugin.settings.MaxVibesSettings
import com.maxvibes.shared.result.Result

/**
 * Main service for MaxVibes plugin.
 * Manages dependencies and provides access to use cases.
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

    val promptService: PromptService by lazy {
        PromptService.getInstance(project)
    }

    val promptPort: PromptPort
        get() = promptService

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
            notificationPort = notificationPort,
            promptPort = promptPort
        )
    }

    // ========== Clipboard Service (for CLIPBOARD mode) ==========

    /** Clipboard interaction service. Always available, lightweight. */
    val clipboardService: ClipboardInteractionService by lazy {
        ClipboardInteractionService(
            contextProvider = projectContextProvider,
            clipboardPort = ClipboardAdapter(),
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = promptPort
        )
    }

    // ========== Cheap LLM (for CHEAP_API mode) ==========

    @Volatile
    private var _cheapLLMService: LLMService? = null

    @Volatile
    private var _cheapContextAwareModifyUseCase: ContextAwareModifyUseCase? = null

    /** Cheap context-aware use case, null until ensureCheapLLMService() is called */
    val cheapContextAwareModifyUseCase: ContextAwareModifyUseCase?
        get() = _cheapContextAwareModifyUseCase

    /**
     * Initializes cheap LLM service. Called when switching to CHEAP_API mode.
     */
    fun ensureCheapLLMService() {
        if (_cheapContextAwareModifyUseCase != null) return

        val settings = MaxVibesSettings.getInstance()

        try {
            val providerType = try {
                LLMProviderType.valueOf(settings.cheapProvider)
            } catch (_: Exception) {
                LLMProviderType.ANTHROPIC
            }

            val baseUrl = when (providerType) {
                LLMProviderType.OLLAMA -> settings.cheapOllamaBaseUrl
                LLMProviderType.DEEPSEEK -> "https://api.deepseek.com"
                else -> null
            }

            val cheapConfig = LLMProviderConfig(
                providerType = providerType,
                apiKey = settings.currentCheapApiKey,
                modelId = settings.cheapModelId,
                baseUrl = baseUrl,
                temperature = settings.cheapTemperature,
                maxTokens = settings.cheapMaxTokens
            )

            val cheapLlm = LangChainLLMService(cheapConfig)
            _cheapLLMService = cheapLlm

            _cheapContextAwareModifyUseCase = ContextAwareModifyService(
                contextProvider = projectContextProvider,
                llmService = cheapLlm,
                codeRepository = codeRepository,
                notificationPort = notificationPort,
                promptPort = promptPort
            )

            LOG.info("Cheap LLM service created: ${settings.cheapProvider} / ${settings.cheapModelId}")
        } catch (e: Exception) {
            LOG.warn("Failed to create cheap LLM service: ${e.message}", e)
        }
    }

    // ========== LLM Service Creation ==========

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

        return try {
            val envService = LLMServiceFactory.createFromEnvironment()
            LOG.info("Using LLM from environment variables: ${envService.getProviderInfo()}")
            envService
        } catch (e: Exception) {
            LOG.info("No environment variables found: ${e.message}")

            if (settings.enableMockFallback) {
                LOG.info("Using MockLLMService (mock fallback enabled)")
                MockLLMService()
            } else {
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

    fun refreshLLMService(): LLMService {
        _llmService = createLLMService()
        // Сбрасываем cheap-сервис чтобы пересоздался при следующем использовании
        _cheapLLMService = null
        _cheapContextAwareModifyUseCase = null
        return _llmService!!
    }

    fun getLLMInfo(): String {
        return when (val service = llmService) {
            is LangChainLLMService -> service.getProviderInfo()
            is MockLLMService -> "Mock (testing mode)"
            is NotConfiguredLLMService -> "Not configured"
            else -> "Unknown"
        }
    }

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
 */
private class NotConfiguredLLMService : LLMService {

    private val configError = LLMError.ConfigurationError(
        "LLM is not configured. Please go to Settings → Tools → MaxVibes to configure an API key."
    )

    override suspend fun chat(
        message: String,
        history: List<ChatMessageDTO>,
        context: ChatContext
    ): Result<ChatResponse, LLMError> {
        return Result.Failure(configError)
    }

    override suspend fun planContext(
        task: String,
        projectContext: ProjectContext,
        prompts: PromptTemplates
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