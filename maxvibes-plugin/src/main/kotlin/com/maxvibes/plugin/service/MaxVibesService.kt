package com.maxvibes.plugin.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.maxvibes.adapter.llm.LangChainLLMService
import com.maxvibes.adapter.llm.LLMServiceFactory
import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import com.maxvibes.adapter.psi.PsiCodeRepository
import com.maxvibes.adapter.psi.context.IntellijIdeErrorsAdapter
import com.maxvibes.adapter.psi.context.PsiProjectContextProvider
import com.maxvibes.application.port.input.AnalyzeCodeUseCase
import com.maxvibes.application.port.input.ContextAwareModifyUseCase
import com.maxvibes.application.port.input.ModifyCodeUseCase
import com.maxvibes.application.port.output.*
import com.maxvibes.application.service.AnalyzeCodeService
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.application.service.ClaudeCodeInteractionService
import com.maxvibes.application.service.ClipboardInteractionService
import com.maxvibes.application.service.ClipboardSessionManager
import com.maxvibes.application.service.ContextAwareModifyService
import com.maxvibes.application.service.ModifyCodeService
import com.maxvibes.application.service.SpecificPromptService
import com.maxvibes.plugin.claudecode.ClaudeCodeProcessAdapter
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.context.ContextRequest
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.plugin.chat.ChatHistoryService
import com.maxvibes.plugin.clipboard.ClipboardAdapter
import com.maxvibes.plugin.settings.MaxVibesSettings
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.maxvibes.application.service.ClaudeCodeActivityTracker

/**
 * Main service for MaxVibes plugin.
 * Manages dependencies and provides access to use cases.
 *
 * Implements [Disposable] so IntelliJ shuts down resources (the Claude Code
 * process and project-scoped coroutines) when the project closes.
 */
@Service(Service.Level.PROJECT)
class MaxVibesService(private val project: Project) : Disposable {

    private val LOG: Logger = Logger.getInstance(MaxVibesService::class.java)

    /**
     * Project-scoped coroutine scope.
     *
     * Owned by this service — cancelled in [dispose] when the project closes.
     * Used by [ClaudeCodeProcessAdapter] for stderr collection and any other
     * background tasks that must not outlive the project.
     */
    private val serviceScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("MaxVibesService")
    )

    init {
        println("[MaxVibesService] init block running for project: ${project.name}")
        val basePath = project.basePath ?: System.getProperty("user.home")
        println("[MaxVibesService] Calling MaxVibesLogger.configure(basePath=$basePath)")
        MaxVibesLogger.configure(basePath)
        MaxVibesLogger.info("MaxVibesService", "Plugin service initialized", mapOf("project" to project.name))
        LOG.info("[MaxVibes] MaxVibesLogger configured, log: $basePath/.maxvibes/logs/maxvibes.log")
    }

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

    val loggerPort: LoggerPort by lazy {
        ProjectLogger(project)
    }

    // ========== Use Cases ==========

    val modifyCodeUseCase: ModifyCodeUseCase by lazy {
        ModifyCodeService(codeRepository, llmService, notificationPort, MaxVibesLogger)
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
            promptPort = promptPort,
            logger = MaxVibesLogger
        )
    }

    // ========== Clipboard Service ==========

    val clipboardService: ClipboardInteractionService by lazy {
        ClipboardInteractionService(
            contextProvider = projectContextProvider,
            clipboardPort = ClipboardAdapter(),
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = promptPort,
            logger = MaxVibesLogger,
            sessionManager = clipboardSessionManager,
            chatSessionRepository = chatSessionRepository
        )
    }

    // ========== Claude Code Service ==========

    /**
     * Explicit [Lazy] delegate so [dispose] can check [Lazy.isInitialized] and
     * avoid spawning the adapter just to immediately tear it down when the user
     * never used Claude Code mode in this project session.
     *
     * The working directory is set to the project's base path so that:
     *  - claude-code can autoload `CLAUDE.md` from the project root;
     *  - error messages reference paths relative to the project, not the IDE cache;
     *  - any sandbox-relative tool behaviour stays scoped to the project tree.
     */
    private val claudeCodeAdapterLazy: Lazy<ClaudeCodeProcessAdapter> = lazy {
        ClaudeCodeProcessAdapter(
            settings = MaxVibesSettings.getInstance(),
            scope = serviceScope,
            workingDirectory = project.basePath
        )
    }

    /**
     * Single per-project [ClaudeCodePort] adapter.
     * The actual `claude` CLI process is spawned on the first send via
     * [ClaudeCodeProcessAdapter.ensureStarted] — not on construction.
     */
    private val claudeCodeAdapter: ClaudeCodeProcessAdapter by claudeCodeAdapterLazy

    /**
     * Application service that orchestrates the Claude Code dialog flow.
     *
     * Uses the same [ClipboardSessionManager] as [clipboardService] — the
     * manager is protocol-agnostic and handles both AWAITING_PASTE (clipboard)
     * and AWAITING_APPROVE (Claude Code) transitions after Step 6.
     */
    val claudeCodeService: ClaudeCodeInteractionService by lazy {
        ClaudeCodeInteractionService(
            contextProvider = projectContextProvider,
            claudeCodePort = claudeCodeAdapter,
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = promptPort,
            logger = MaxVibesLogger,
            sessionManager = clipboardSessionManager,
            chatSessionRepository = chatSessionRepository,
            activityTracker = claudeCodeActivityTracker
        )
    }

    /**
     * In-memory store + observer hub for transient Claude Code live-activity events.
     *
     * One instance per project — both the service (writes via doSend) and the UI
     * (subscribes via addListener / polls via currentFor) share this singleton.
     * Lifetime is tied to the project; no persistence across IDE restarts by design.
     */
    val claudeCodeActivityTracker: ClaudeCodeActivityTracker by lazy {
        ClaudeCodeActivityTracker()
    }

    // ========== Cheap LLM ==========

    @Volatile
    private var _cheapLLMService: LLMService? = null

    @Volatile
    private var _cheapContextAwareModifyUseCase: ContextAwareModifyUseCase? = null

    /**
     * Lazy-initialized use case backed by the cheap LLM configuration.
     * Creates the service on first access; returns null if creation fails
     * (e.g. cheap provider is not configured in settings).
     */
    val cheapContextAwareModifyUseCase: ContextAwareModifyUseCase?
        get() {
            if (_cheapContextAwareModifyUseCase == null) {
                initCheapLLMService()
            }
            return _cheapContextAwareModifyUseCase
        }

    /**
     * Creates the cheap LLM service and its associated use case from current settings.
     * Idempotent — skips creation if already initialized.
     */
    private fun initCheapLLMService() {
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
                promptPort = promptPort,
                logger = MaxVibesLogger
            )
            LOG.info("Cheap LLM service created: ${settings.cheapProvider} / ${settings.cheapModelId}")
            MaxVibesLogger.info(
                "Service",
                "cheapLLM created",
                mapOf("provider" to settings.cheapProvider, "model" to settings.cheapModelId)
            )
        } catch (e: Exception) {
            LOG.warn("Failed to create cheap LLM service: ${e.message}", e)
            MaxVibesLogger.error("Service", "cheapLLM creation failed", e)
        }
    }

    /**
     * No-op. Kept for source compatibility while callers are migrated.
     *
     * Cheap LLM service now initializes lazily on the first access to
     * [cheapContextAwareModifyUseCase] — no manual call required.
     */
    @Deprecated(
        message = "Cheap LLM service is initialized lazily. Access cheapContextAwareModifyUseCase directly.",
        replaceWith = ReplaceWith("cheapContextAwareModifyUseCase")
    )
    fun ensureCheapLLMService() {
        // Intentional no-op: lazy init happens inside the cheapContextAwareModifyUseCase getter.
    }

    // ========== LLM Service Creation ==========

    private fun createLLMService(): LLMService {
        val settings = MaxVibesSettings.getInstance()
        return try {
            if (settings.isConfigured) {
                LOG.info("Creating real LLM service: ${settings.provider} / ${settings.modelId}")
                MaxVibesLogger.info(
                    "Service",
                    "createLLM",
                    mapOf("provider" to settings.provider, "model" to settings.modelId)
                )
                createRealLLMService(settings)
            } else {
                handleNotConfigured(settings)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to create LLM service: ${e.message}", e)
            MaxVibesLogger.error("Service", "createLLM failed", e)
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

    val ideErrorsPort: IdeErrorsPort by lazy { IntellijIdeErrorsAdapter(project) }
    val chatSessionRepository: ChatSessionRepository by lazy {
        ChatHistoryService.getInstance(project)
    }
    val chatTreeService: ChatTreeService by lazy {
        ChatTreeService(chatSessionRepository)
    }

    /** Manages session state transitions for clipboard and Claude Code modes (IDLE → SESSION_ACTIVE → AWAITING_PASTE / AWAITING_APPROVE). */
    val clipboardSessionManager: ClipboardSessionManager by lazy {
        ClipboardSessionManager(
            repository = chatSessionRepository,
            logger = loggerPort
        )
    }
    val specificPromptRepository: SpecificPromptRepository by lazy {
        FileSpecificPromptRepository.forProject(
            project.basePath ?: System.getProperty("user.home")
        )
    }
    val specificPromptService: SpecificPromptService by lazy {
        SpecificPromptService(specificPromptRepository)
    }

    // ========== Lifecycle ==========

    /**
     * Called by IntelliJ when the project closes.
     *
     * Order of teardown matters:
     *  1. Shut down the Claude Code process (only if it was actually started)
     *     so the OS reclaims its PID before we drop our references.
     *  2. Cancel the project-scoped coroutine scope so any in-flight background
     *     tasks (stderr collectors, etc.) terminate cleanly.
     *
     * Each step is wrapped in [runCatching] so a failure in one step does not
     * prevent the others from completing.
     */
    override fun dispose() {
        // Step 1: shutdown the adapter only if it was actually constructed.
        // Touching the lazy property would force construction — guard via the delegate.
        if (claudeCodeAdapterLazy.isInitialized()) {
            runCatching { claudeCodeAdapter.shutdown() }
                .onFailure { LOG.warn("ClaudeCodeProcessAdapter.shutdown failed: ${it.message}", it) }
        }
        // Step 2: cancel the coroutine scope.
        runCatching { serviceScope.cancel() }
            .onFailure { LOG.warn("serviceScope.cancel failed: ${it.message}", it) }
        MaxVibesLogger.info("MaxVibesService", "disposed", mapOf("project" to project.name))
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
        "LLM is not configured. Please go to Settings \u2192 Tools \u2192 MaxVibes to configure an API key."
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
