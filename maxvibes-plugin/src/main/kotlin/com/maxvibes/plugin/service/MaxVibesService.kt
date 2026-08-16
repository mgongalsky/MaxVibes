package com.maxvibes.plugin.service

import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.maxvibes.adapter.llm.LangChainLLMService
import com.maxvibes.adapter.llm.LLMServiceFactory
import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import com.maxvibes.adapter.psi.context.IntellijIdeErrorsAdapter
import com.maxvibes.adapter.psi.context.PsiProjectContextProvider
import com.maxvibes.application.port.input.AnalyzeCodeUseCase
import com.maxvibes.application.port.input.ContextAwareModifyUseCase
import com.maxvibes.application.port.input.ModifyCodeUseCase
import com.maxvibes.application.port.output.*
import com.maxvibes.application.service.AnalyzeCodeService
import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.application.service.ClipboardInteractionService
import com.maxvibes.application.service.ClipboardSessionManager
import com.maxvibes.application.service.ContextAwareModifyService
import com.maxvibes.application.service.ModifyCodeService
import com.maxvibes.application.service.SpecificPromptService
import com.maxvibes.plugin.claudecode.ClaudeCodeProcessAdapter
import com.maxvibes.plugin.claudecode.ClaudeCodeSessionLogWriter
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.code.CodeView
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.context.ContextRequest
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationError
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.plugin.chat.ChatHistoryService
import com.maxvibes.plugin.clipboard.ClipboardAdapter
import com.maxvibes.plugin.settings.MaxVibesSettings
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.maxvibes.application.service.AgentStreamHub
import com.maxvibes.application.port.input.ExecuteCommandUseCase
import com.maxvibes.application.port.output.CommandRunnerPort
import com.maxvibes.application.service.CommandExecutionService
import com.maxvibes.plugin.command.ProcessCommandRunner
import com.maxvibes.application.service.CodingAgentInteractionService
import com.maxvibes.plugin.codex.CodexAppServerAdapter
import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.application.port.input.RunCheckUseCase
import com.maxvibes.application.service.CheckExecutionService
import com.maxvibes.plugin.check.CheckRunnerProvider

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

    /**
     * Language-dispatched [CodeRepository]. See [createCodeRepository] for the
     * dispatch rules and classloading constraints.
     */
    val codeRepository: CodeRepository by lazy {
        createCodeRepository()
    }

    /**
     * Runtime dispatch of the PSI adapter by available language support.
     *
     * The Kotlin and Python plugin dependencies are OPTIONAL (plugin.xml), so
     * concrete repository classes must never be referenced from this
     * always-loaded service. Construction is isolated in [KotlinAdapterProvider]
     * and [PythonAdapterProvider]; each is touched only after the corresponding
     * language is confirmed present via [Language.findLanguageByID] — otherwise
     * class loading would fail with NoClassDefFoundError.
     *
     * Priority: Kotlin over Python — keeps existing IDEA behaviour intact when
     * both plugins are installed. Known limitation: a mixed IDE (e.g. PyCharm
     * with the Kotlin plugin installed) gets the Kotlin adapter; per-project
     * detection is a possible follow-up (see docs/features/PyCharm/STEP_9_DI.md).
     */
    private fun createCodeRepository(): CodeRepository {
        val kotlinAvailable = Language.findLanguageByID("kotlin") != null
        val pythonAvailable = Language.findLanguageByID("Python") != null
        MaxVibesLogger.info(
            "Service",
            "codeRepository dispatch",
            mapOf("kotlin" to kotlinAvailable.toString(), "python" to pythonAvailable.toString())
        )
        return when {
            kotlinAvailable -> KotlinAdapterProvider.createCodeRepository(project)
            pythonAvailable -> PythonAdapterProvider.createCodeRepository(project)
            else -> {
                LOG.warn("No supported language plugin (Kotlin/Python) — code operations disabled")
                UnsupportedLanguageCodeRepository()
            }
        }
    }

    val projectContextProvider: ProjectContextPort by lazy {
        PsiProjectContextProvider(project)
    }

    val promptService: PromptService by lazy {
        PromptService.getInstance(project).apply {
            skillCatalogProvider = { specificPromptService.skillCatalogSection() }
        }
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
            chatSessionRepository = chatSessionRepository,
            specificPromptService = specificPromptService
        )
    }

    // ========== Command Execution ==========

    /** Executes LLM-requested shell commands (GeneralCommandLine + CapturingProcessHandler, cwd = project root). */
    val commandRunner: CommandRunnerPort by lazy {
        ProcessCommandRunner(project)
    }

    /** Soft validation warnings + execution + LLM-facing result formatting. */
    val executeCommandUseCase: ExecuteCommandUseCase by lazy {
        CommandExecutionService(
            runner = commandRunner,
            logger = MaxVibesLogger
        )
    }

    /** Executes checks and formats their outcome for the agent. */
    val runCheckUseCase: RunCheckUseCase by lazy {
        CheckExecutionService(runner = checkRunner)
    }

    /** Runs LLM-requested checks with IDE means: compiler API for builds, run configurations for tests. */
    val checkRunner: CheckRunnerPort by lazy {
        CheckRunnerProvider.forProject(project)
    }

    // ========== Claude Code Service ==========

    /**
     * Per-dialog verbose transcript writer for the Claude Code mode
     * (`.maxvibes/logs/claude-code/<chatSessionId>.log`).
     *
     * Explicit [Lazy] so [dispose] can close it only if it was ever created —
     * mirrors the [claudeCodeAdapterLazy] pattern. Exposed to the rest of the
     * plugin as [claudeCodeSessionLog] (port type); the UI uses
     * [ClaudeCodeSessionLogPort.logFilePath] to open the transcript in the editor.
     */
    private val claudeCodeSessionLogLazy: Lazy<ClaudeCodeSessionLogWriter> = lazy {
        ClaudeCodeSessionLogWriter(project.basePath ?: System.getProperty("user.home"))
    }

    /** Per-dialog Claude Code transcript (see [ClaudeCodeSessionLogPort]). */
    val claudeCodeSessionLog: ClaudeCodeSessionLogPort
        get() = claudeCodeSessionLogLazy.value

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
            workingDirectory = project.basePath,
            sessionLog = claudeCodeSessionLog,
            streamSink = agentStreamHub
        )
    }
    private val codexAdapterLazy: Lazy<CodexAppServerAdapter> = lazy {
        CodexAppServerAdapter(
            settings = MaxVibesSettings.getInstance(),
            scope = serviceScope,
            workingDirectory = project.basePath,
            sessionLog = claudeCodeSessionLog,
            streamSink = agentStreamHub
        )
    }

    /**
     * Single per-project [ClaudeCodePort] adapter.
     * The actual `claude` CLI process is spawned on the first send via
     * [ClaudeCodeProcessAdapter.ensureStarted] — not on construction.
     */
    private val claudeCodeAdapter: ClaudeCodeProcessAdapter by claudeCodeAdapterLazy
    private val codexAdapter: CodexAppServerAdapter by codexAdapterLazy
    private fun selectedCodingAgentProvider(): CodingAgentProvider {
        val raw = MaxVibesSettings.getInstance().codingAgentProvider
        val resolved = runCatching { CodingAgentProvider.valueOf(raw) }
            .getOrDefault(CodingAgentProvider.CLAUDE_CODE)
        MaxVibesLogger.info(
            "MaxVibesService",
            "coding agent resolved",
            mapOf("raw" to raw, "resolved" to resolved.name)
        )
        return resolved
    }

    private val claudeCodeInteractionServiceLazy: Lazy<CodingAgentInteractionService> = lazy {
        CodingAgentInteractionService(
            contextProvider = projectContextProvider,
            claudeCodePort = claudeCodeAdapter,
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = promptPort,
            logger = MaxVibesLogger,
            sessionManager = clipboardSessionManager,
            chatSessionRepository = chatSessionRepository,
            sessionLog = claudeCodeSessionLog,
            specificPromptService = specificPromptService,
            streamHub = agentStreamHub,
            provider = CodingAgentProvider.CLAUDE_CODE
        )
    }
    private val codexInteractionServiceLazy: Lazy<CodingAgentInteractionService> = lazy {
        CodingAgentInteractionService(
            contextProvider = projectContextProvider,
            claudeCodePort = codexAdapter,
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = promptPort,
            logger = MaxVibesLogger,
            sessionManager = clipboardSessionManager,
            chatSessionRepository = chatSessionRepository,
            sessionLog = claudeCodeSessionLog,
            specificPromptService = specificPromptService,
            streamHub = agentStreamHub,
            provider = CodingAgentProvider.CODEX
        )
    }

    val claudeCodeService: CodingAgentInteractionService
        get() = when (selectedCodingAgentProvider()) {
            CodingAgentProvider.CLAUDE_CODE -> claudeCodeInteractionServiceLazy.value
            CodingAgentProvider.CODEX -> codexInteractionServiceLazy.value
        }

    /** Display name of the coding agent [claudeCodeService] will actually talk to. */
    val activeCodingAgentName: String
        get() = selectedCodingAgentProvider().displayName

    /**
     * Live-stream hub for the Claude Code mode: the adapter emits AgentStreamEvents
     * into it (as AgentStreamSink), the UI subscribes via addListener (Set 2).
     * Session attribution follows the sessionLog pattern - the interaction service
     * calls begin(sessionId) before every transport call.
     */
    val agentStreamHub: AgentStreamHub by lazy {
        AgentStreamHub()
    }

    fun abortClaudeCode() {
        if (claudeCodeAdapterLazy.isInitialized()) {
            runCatching { claudeCodeAdapter.abort() }
                .onFailure { LOG.warn("Claude Code abort failed: ${it.message}", it) }
        }
        if (codexAdapterLazy.isInitialized()) {
            runCatching { codexAdapter.abort() }
                .onFailure { LOG.warn("Codex abort failed: ${it.message}", it) }
        }
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

    override fun dispose() {
        if (claudeCodeAdapterLazy.isInitialized()) {
            runCatching { claudeCodeAdapter.shutdown() }
                .onFailure { LOG.warn("ClaudeCodeProcessAdapter.shutdown failed: ${it.message}", it) }
        }
        if (codexAdapterLazy.isInitialized()) {
            runCatching { codexAdapter.shutdown() }
                .onFailure { LOG.warn("CodexAppServerAdapter.shutdown failed: ${it.message}", it) }
        }
        if (claudeCodeSessionLogLazy.isInitialized()) {
            runCatching { claudeCodeSessionLogLazy.value.close() }
                .onFailure { LOG.warn("ClaudeCodeSessionLogWriter.close failed: ${it.message}", it) }
        }
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

/**
 * Fallback [CodeRepository] used when neither the Kotlin nor the Python plugin
 * is available in the running IDE.
 *
 * Mirrors [NotConfiguredLLMService]: every operation fails fast with a clear,
 * user-facing message instead of crashing with NoClassDefFoundError at
 * adapter-construction time. Selected by [MaxVibesService.createCodeRepository].
 */
private class UnsupportedLanguageCodeRepository : CodeRepository {

    private val message =
        "No supported language plugin found (Kotlin or Python). MaxVibes code operations are unavailable in this IDE."

    override suspend fun getFileContent(path: ElementPath): Result<String, CodeRepositoryError> {
        return Result.Failure(CodeRepositoryError.ReadError(message))
    }

    override suspend fun getElement(path: ElementPath): Result<CodeElement, CodeRepositoryError> {
        return Result.Failure(CodeRepositoryError.ReadError(message))
    }

    override suspend fun findElements(
        basePath: ElementPath,
        kinds: Set<ElementKind>?,
        namePattern: Regex?
    ): Result<List<CodeElement>, CodeRepositoryError> {
        return Result.Failure(CodeRepositoryError.ReadError(message))
    }

    override suspend fun applyModification(modification: Modification): ModificationResult {
        return ModificationResult.Failure(
            modification = modification,
            error = ModificationError.InvalidOperation(message)
        )
    }

    override suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult> {
        return modifications.map { applyModification(it) }
    }

    override suspend fun exists(path: ElementPath): Boolean = false

    override suspend fun validateSyntax(content: String): Result<Unit, CodeRepositoryError> {
        return Result.Failure(CodeRepositoryError.ValidationError(message))
    }

    override suspend fun getCodeView(request: CodeViewRequest): CodeView {
        error(message)
    }
}
