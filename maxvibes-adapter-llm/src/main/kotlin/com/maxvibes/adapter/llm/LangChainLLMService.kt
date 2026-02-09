package com.maxvibes.adapter.llm

import com.maxvibes.adapter.llm.aiservice.ChatAiService
import com.maxvibes.adapter.llm.aiservice.PlanningAiService
import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import com.maxvibes.adapter.llm.dto.ChatResponseDTO
import com.maxvibes.adapter.llm.dto.ModificationDTO
import com.maxvibes.adapter.llm.dto.PlanningResultDTO
import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.context.ContextRequest
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.shared.result.Result
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.service.AiServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Duration

/**
 * LLM Service implementation using LangChain4j 1.11.0 with AiServices.
 *
 * Uses AiServices for structured output via tool calling:
 * - Planning phase: PlanningAiService -> PlanningResultDTO (guaranteed structure)
 * - Chat phase: ChatAiService -> ChatResponseDTO (message + modifications, no regex!)
 *
 * Falls back to raw ChatModel + regex parsing if AiServices fails.
 *
 * IMPORTANT: All AiServices calls must use [withPluginClassLoader] to avoid
 * Jackson ServiceLoader conflicts with IntelliJ's bundled Jackson.
 */
class LangChainLLMService(
    private val config: LLMProviderConfig
) : LLMService {

    // ==================== ClassLoader Fix ====================

    /**
     * Executes [block] with Thread.contextClassLoader set to this plugin's classloader.
     *
     * WHY: LangChain4j's JacksonJsonCodec uses ObjectMapper.findAndRegisterModules()
     * which triggers Java's ServiceLoader. In IntelliJ plugins, ServiceLoader fails because
     * Jackson's KotlinModule (from plugin classloader) appears as "not a subtype" of
     * jackson-databind's Module class (from IntelliJ's classloader).
     *
     * FIX: Temporarily set contextClassLoader to plugin's classloader so ServiceLoader
     * finds our custom IntelliJSafeJsonCodecFactory via SPI, avoiding findAndRegisterModules().
     */
    private fun <T> withPluginClassLoader(block: () -> T): T {
        val originalClassLoader = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = this::class.java.classLoader
            block()
        } finally {
            Thread.currentThread().contextClassLoader = originalClassLoader
        }
    }

    init {
        // CRITICAL: Force-initialize dev.langchain4j.internal.Json with the plugin classloader
        // BEFORE any AiServices or Json usage. Json.<clinit> is a one-shot static initializer —
        // if it fails, the class is dead for the entire JVM session ("Could not initialize class").
        //
        // By setting contextClassLoader to plugin's classloader, ServiceLoader.load(JsonCodecFactory)
        // will find our META-INF/services/dev.langchain4j.spi.json.JsonCodecFactory SPI file
        // and use IntelliJSafeJsonCodecFactory instead of the broken default JacksonJsonCodec.
        withPluginClassLoader {
            try {
                Class.forName("dev.langchain4j.internal.Json", true, this::class.java.classLoader)
                log("[LangChainLLMService] Json class initialized successfully with plugin classloader")
            } catch (e: Throwable) {
                log("[LangChainLLMService] WARNING: Json class init failed: ${e.message}")
                log("[LangChainLLMService] AiServices structured output will be unavailable, using fallback")
            }
        }
    }

    private val chatModel: ChatModel by lazy { createChatModel() }

    // AiService proxy for planning - created lazily
    private val planningService: PlanningAiService by lazy {
        withPluginClassLoader { createPlanningService() }
    }

    /** UTF-8 safe logging (System.out.println breaks Unicode on Windows) */
    private fun log(message: String) {
        val writer = java.io.OutputStreamWriter(System.out, Charsets.UTF_8)
        writer.write(message)
        writer.write("\n")
        writer.flush()
    }

    // ==================== Model Creation ====================

    private fun createChatModel(): ChatModel {
        return when (config.providerType) {
            LLMProviderType.OPENAI -> {
                val isReasoningModel = config.modelId.contains("gpt-5") ||
                        config.modelId.contains("o1") ||
                        config.modelId.contains("o3")
                val maxTokens = if (isReasoningModel) 32768   else config.maxTokens

                OpenAiChatModel.builder()
                    .apiKey(config.apiKey)
                    .modelName(resolveOpenAIModel(config.modelId))
                    .temperature(config.temperature)
                    .maxCompletionTokens(maxTokens)
                    .timeout(Duration.ofSeconds(if (isReasoningModel) 300 else 120))
                    .build()
            }

            LLMProviderType.ANTHROPIC -> AnthropicChatModel.builder()
                .apiKey(config.apiKey)
                .modelName(resolveAnthropicModel(config.modelId))
                .temperature(config.temperature)
                .maxTokens(config.maxTokens.coerceAtLeast(32768  ))
                .timeout(Duration.ofSeconds(180))
                .build()

            LLMProviderType.OLLAMA -> OllamaChatModel.builder()
                .baseUrl(config.baseUrl ?: "http://localhost:11434")
                .modelName(config.modelId)
                .temperature(config.temperature)
                .timeout(Duration.ofSeconds(300))
                .build()
        }
    }

    // ==================== AiService Creation ====================

    private fun createPlanningService(): PlanningAiService {
        return AiServices.builder(PlanningAiService::class.java)
            .chatModel(chatModel)
            .build()
    }

    /**
     * Creates a ChatAiService with dynamic system prompt and context injection.
     * Must be created per-request because system prompt and context change.
     */
    private fun createChatService(
        systemPrompt: String,
        history: List<ChatMessageDTO>,
        contextBlock: String
    ): ChatAiService {
        return AiServices.builder(ChatAiService::class.java)
            .chatModel(chatModel)
            .chatRequestTransformer { request ->
                // Build the full message list with system prompt, history, and context
                val messages = mutableListOf<ChatMessage>()

                // 1. System prompt
                messages.add(SystemMessage.from(systemPrompt))

                // 2. Chat history
                history
                    .filter { it.role != ChatRole.SYSTEM && it.content.isNotBlank() }
                    .forEach { msg ->
                        when (msg.role) {
                            ChatRole.USER -> messages.add(UserMessage.from(msg.content))
                            ChatRole.ASSISTANT -> messages.add(AiMessage.from(msg.content))
                            else -> {}
                        }
                    }

                // 3. Current user message (from AiService) + context
                val originalUserMessages = request.messages().filterIsInstance<UserMessage>()
                val userText = originalUserMessages.lastOrNull()?.singleText() ?: ""

                val fullUserMessage = if (contextBlock.isNotBlank()) {
                    "$userText\n\n$contextBlock"
                } else {
                    userText
                }
                messages.add(UserMessage.from(fullUserMessage))

                // Rebuild request with our messages, keep tool specs from AiServices
                ChatRequest.builder()
                    .messages(messages)
                    .parameters(request.parameters())
                    .build()
            }
            .build()
    }

    // ==================== Model Resolution ====================

    private fun resolveOpenAIModel(modelId: String): String = when (modelId) {
        "gpt-5.2", "gpt5", "gpt-5" -> "gpt-5.2"
        "gpt-4o" -> "gpt-4o"
        "gpt-4o-mini" -> "gpt-4o-mini"
        "gpt-4-turbo" -> "gpt-4-turbo"
        "gpt-4" -> "gpt-4"
        "gpt-3.5-turbo" -> "gpt-3.5-turbo"
        else -> modelId
    }

    private fun resolveAnthropicModel(modelId: String): String = when (modelId) {
        // Claude 4.6 (latest, Feb 2026)
        "claude-opus-4-6", "claude-opus-4.6", "claude-4.6" -> "claude-opus-4-6"
        // Claude 4.5 family (Sep-Nov 2025)
        "claude-opus-4-5", "claude-opus-4.5", "claude-4.5-opus" -> "claude-opus-4-5-20251101"
        "claude-sonnet-4-5", "claude-sonnet-4.5", "claude-4.5-sonnet" -> "claude-sonnet-4-5-20250929"
        "claude-haiku-4-5", "claude-haiku-4.5", "claude-4.5-haiku" -> "claude-haiku-4-5-20251001"
        // Claude 4 (May 2025)
        "claude-sonnet-4", "claude-4-sonnet" -> "claude-sonnet-4-20250514"
        // Claude 4.1 (Aug 2025)
        "claude-opus-4-1", "claude-opus-4.1" -> "claude-opus-4-1-20250805"
        // Claude 3.x legacy (deprecated)
        "claude-3-opus" -> "claude-3-opus-20240229"
        "claude-3-sonnet" -> "claude-3-sonnet-20240229"
        "claude-3-haiku" -> "claude-3-haiku-20240307"
        // Pass through exact model IDs
        else -> modelId
    }

    // ==================== Chat with History (AiServices) ====================

    override suspend fun chat(
        message: String,
        history: List<ChatMessageDTO>,
        context: ChatContext
    ): Result<ChatResponse, LLMError> = withContext(Dispatchers.IO) {
        try {
            log("[LangChainLLMService] Chat request via AiServices")
            log("[LangChainLLMService] Provider: ${config.providerType}, Model: ${config.modelId}")
            log("[LangChainLLMService] History: ${history.size} messages")
            log("[LangChainLLMService] Context files: ${context.gatheredFiles.size}")

            // Build dynamic parts
            val systemPrompt = buildChatSystemPrompt(context)
            val contextBlock = buildContextBlock(context.gatheredFiles)

            // Try AiServices first (structured output via tool calling)
            val chatResponse = try {
                withPluginClassLoader {
                    val chatService = createChatService(systemPrompt, history, contextBlock)
                    val dto: ChatResponseDTO = chatService.chat(message)

                    log("[LangChainLLMService] AiServices OK: ${dto.modifications.size} modifications")

                    ChatResponse(
                        message = dto.message,
                        modifications = dto.modifications.mapNotNull { convertModification(it) },
                        requestedFiles = extractFileRequests(dto.message)
                    )
                }
            } catch (e: Throwable) {
                log("[LangChainLLMService] AiServices failed: ${e.message}")
                log("[LangChainLLMService] Falling back to raw ChatModel + regex parsing")

                // Fallback: raw ChatModel with regex parsing
                chatViaRawModel(message, history, systemPrompt, contextBlock)
            }

            log("[LangChainLLMService] Final result: ${chatResponse.modifications.size} modifications")
            Result.Success(chatResponse)

        } catch (e: Exception) {
            log("[LangChainLLMService] Chat error: ${e.message}")
            e.printStackTrace()
            Result.Failure(LLMError.NetworkError(e.message ?: "Chat failed"))
        }
    }

    /**
     * Fallback: use raw ChatModel when AiServices doesn't work
     * (e.g., model doesn't support tool calling, or response too large for tools)
     */
    private fun chatViaRawModel(
        message: String,
        history: List<ChatMessageDTO>,
        systemPrompt: String,
        contextBlock: String
    ): ChatResponse {
        val messages = buildRawChatMessages(message, history, systemPrompt, contextBlock)
        val response = chatModel.chat(messages)
        val content = response.aiMessage().text()

        log("[LangChainLLMService] Raw response length: ${content.length}")
        return parseChatResponse(content)
    }

    private fun buildRawChatMessages(
        message: String,
        history: List<ChatMessageDTO>,
        systemPrompt: String,
        contextBlock: String
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(systemPrompt))

        history
            .filter { it.role != ChatRole.SYSTEM && it.content.isNotBlank() }
            .forEach { msg ->
                when (msg.role) {
                    ChatRole.USER -> messages.add(UserMessage.from(msg.content))
                    ChatRole.ASSISTANT -> messages.add(AiMessage.from(msg.content))
                    else -> {}
                }
            }

        val fullMessage = if (contextBlock.isNotBlank()) {
            "$message\n\n$contextBlock"
        } else {
            message
        }
        messages.add(UserMessage.from(fullMessage.ifBlank { "Help me with my code" }))

        return messages
    }

    private fun buildContextBlock(gatheredFiles: Map<String, String>): String {
        if (gatheredFiles.isEmpty()) return ""
        return buildString {
            appendLine("=== CURRENT CODE CONTEXT (${gatheredFiles.size} files) ===")
            gatheredFiles.forEach { (path, content) ->
                appendLine("--- $path ---")
                appendLine(content.take(5000))
                appendLine()
            }
        }
    }

    private fun buildChatSystemPrompt(context: ChatContext): String {
        val template = context.prompts.chatSystem.ifBlank { DEFAULT_CHAT_SYSTEM_PROMPT }
        return applyPromptVariables(template, context.projectContext)
    }

    private fun applyPromptVariables(template: String, projectContext: ProjectContext): String {
        return template
            .replace("{{projectName}}", projectContext.name)
            .replace("{{language}}", projectContext.techStack.language)
            .replace("{{buildTool}}", projectContext.techStack.buildTool ?: "unknown")
            .replace("{{frameworks}}", projectContext.techStack.frameworks.joinToString(", ").ifEmpty { "none" })
    }

    // ==================== Phase 1: Planning (AiServices) ====================

    override suspend fun planContext(
        task: String,
        projectContext: ProjectContext,
        prompts: PromptTemplates
    ): Result<ContextRequest, LLMError> = withContext(Dispatchers.IO) {
        try {
            log("[LangChainLLMService] Planning phase via AiServices")
            log("[LangChainLLMService] Task: $task")

            val result: PlanningResultDTO = try {
                withPluginClassLoader {
                    planningService.planContext(
                        task = task,
                        projectName = projectContext.name,
                        rootPath = projectContext.rootPath,
                        language = projectContext.techStack.language,
                        buildTool = projectContext.techStack.buildTool ?: "unknown",
                        frameworksLine = if (projectContext.techStack.frameworks.isNotEmpty())
                            "Frameworks: ${projectContext.techStack.frameworks.joinToString()}" else "",
                        descriptionBlock = projectContext.description?.let {
                            "=== PROJECT DESCRIPTION ===\n${it.take(2000)}"
                        } ?: "",
                        architectureBlock = projectContext.architecture?.let {
                            "=== ARCHITECTURE ===\n${it.take(3000)}"
                        } ?: "",
                        fileTree = projectContext.fileTree.toCompactString(maxDepth = 4)
                    )
                }
            } catch (e: Throwable) {
                log("[LangChainLLMService] AiServices planning failed: ${e.message}")
                log("[LangChainLLMService] Falling back to raw planning")
                return@withContext planContextRaw(task, projectContext, prompts)
            }

            log("[LangChainLLMService] Planning OK: ${result.requestedFiles.size} files")

            Result.Success(
                ContextRequest(
                    requestedFiles = result.requestedFiles,
                    reasoning = result.reasoning
                )
            )
        } catch (e: Exception) {
            log("[LangChainLLMService] Planning error: ${e.message}")
            e.printStackTrace()
            Result.Failure(LLMError.NetworkError(e.message ?: "Planning failed"))
        }
    }

    /** Fallback planning using raw ChatModel */
    private fun planContextRaw(
        task: String,
        projectContext: ProjectContext,
        prompts: PromptTemplates
    ): Result<ContextRequest, LLMError> {
        return try {
            val systemPrompt = prompts.planningSystem.ifBlank { DEFAULT_PLANNING_SYSTEM_PROMPT }
            val userPrompt = buildPlanningUserPrompt(task, projectContext)

            val response = chatModel.chat(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
            )

            val content = response.aiMessage().text()
            val contextRequest = parsePlanningResponse(content)
            Result.Success(contextRequest)
        } catch (e: Exception) {
            Result.Failure(LLMError.NetworkError(e.message ?: "Planning failed"))
        }
    }

    // ==================== DTO Conversion ====================

    /** Convert ModificationDTO (from AiServices) to domain Modification */
    private fun convertModification(dto: ModificationDTO): Modification? {
        if (dto.type.isBlank() || dto.path.isBlank()) return null

        val elementPath = ElementPath(dto.path)
        val elementKind = try { ElementKind.valueOf(dto.elementKind.uppercase()) } catch (e: Exception) { ElementKind.FILE }
        val position = try { InsertPosition.valueOf(dto.position.uppercase()) } catch (e: Exception) { InsertPosition.LAST_CHILD }

        return when (dto.type.uppercase()) {
            "CREATE_FILE" -> Modification.CreateFile(targetPath = elementPath, content = dto.content)
            "REPLACE_FILE" -> Modification.ReplaceFile(targetPath = elementPath, newContent = dto.content)
            "DELETE_FILE" -> Modification.DeleteFile(targetPath = elementPath)
            "CREATE_ELEMENT" -> Modification.CreateElement(targetPath = elementPath, elementKind = elementKind, content = dto.content, position = position)
            "REPLACE_ELEMENT" -> Modification.ReplaceElement(targetPath = elementPath, newContent = dto.content)
            "DELETE_ELEMENT" -> Modification.DeleteElement(targetPath = elementPath)
            else -> null
        }
    }

    // ==================== Fallback Response Parsing (improved) ====================

    /**
     * Improved parseChatResponse with multiple extraction patterns.
     * Used as fallback when AiServices doesn't work.
     */
    private fun parseChatResponse(response: String): ChatResponse {
        // Pattern 1: ```json ... ``` blocks
        val jsonBlockPattern = Regex("```json\\s*\\n([\\s\\S]*?)\\n\\s*```")
        // Pattern 2: ``` ... ``` blocks containing "modifications"
        val codeBlockPattern = Regex("```\\s*\\n([\\s\\S]*?)\\n\\s*```")
        // Pattern 3: raw JSON with "modifications" key
        val rawJsonPattern = Regex("\\{\\s*\"modifications\"\\s*:\\s*\\[", RegexOption.MULTILINE)

        val jsonMatch = jsonBlockPattern.find(response)
            ?: codeBlockPattern.findAll(response)
                .firstOrNull { it.groupValues[1].contains("\"modifications\"") }

        val message: String
        val modifications: List<Modification>

        if (jsonMatch != null) {
            val beforeJson = response.substring(0, jsonMatch.range.first).trim()
            val afterJson = response.substring(jsonMatch.range.last + 1).trim()
            message = cleanResponseForDisplay(beforeJson, afterJson)

            modifications = try {
                val jsonContent = jsonMatch.groupValues[1]
                val jsonObject = Json.parseToJsonElement(jsonContent).jsonObject
                val modificationsArray = jsonObject["modifications"]?.jsonArray ?: emptyList()
                modificationsArray.mapNotNull { parseModificationElement(it.jsonObject) }
            } catch (e: Throwable) {
                log("[LangChainLLMService] Failed to parse JSON in chat: ${e.message}")
                emptyList()
            }
        } else if (rawJsonPattern.containsMatchIn(response)) {
            val jsonStart = response.indexOf("{\"modifications\"")
                .takeIf { it >= 0 } ?: response.indexOf("{ \"modifications\"")
                .takeIf { it >= 0 } ?: -1

            if (jsonStart >= 0) {
                message = cleanResponseForDisplay(response.substring(0, jsonStart).trim(), "")
                modifications = try {
                    val jsonContent = response.substring(jsonStart)
                    val end = findMatchingBrace(jsonContent)
                    val jsonStr = jsonContent.substring(0, end + 1)
                    val jsonObject = Json.parseToJsonElement(jsonStr).jsonObject
                    val modificationsArray = jsonObject["modifications"]?.jsonArray ?: emptyList()
                    modificationsArray.mapNotNull { parseModificationElement(it.jsonObject) }
                } catch (e: Throwable) {
                    log("[LangChainLLMService] Raw JSON parse failed: ${e.message}")
                    emptyList()
                }
            } else {
                message = response.trim()
                modifications = emptyList()
            }
        } else {
            message = response.trim()
            modifications = emptyList()
        }

        val finalMessage = if (message.isBlank() && modifications.isNotEmpty()) {
            "Code changes ready to apply (${modifications.size} modification(s))"
        } else {
            message
        }

        return ChatResponse(
            message = finalMessage,
            modifications = modifications,
            requestedFiles = extractFileRequests(finalMessage)
        )
    }

    private fun cleanResponseForDisplay(before: String, after: String): String {
        val combined = if (after.isNotBlank()) "$before\n$after" else before
        return combined
            .replace(Regex("```json\\s*\\n[\\s\\S]*?\\n\\s*```"), "")
            .replace(Regex("```\\s*\\n\\s*\\{[\\s\\S]*?\\}\\s*\\n\\s*```"), "")
            .trim()
    }

    private fun findMatchingBrace(json: String): Int {
        var depth = 0
        var inString = false
        var escape = false
        for (i in json.indices) {
            val c = json[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth == 0) return i }
        }
        return json.lastIndexOf('}')
    }

    private fun extractFileRequests(text: String): List<String> {
        val patterns = listOf(
            Regex("(?:need|want|see|show|provide).*?[\"'`]([\\w/]+\\.kt)[\"'`]", RegexOption.IGNORE_CASE),
            Regex("(?:can you|could you|please).*?(?:share|show|provide).*?([\\w/]+\\.kt)", RegexOption.IGNORE_CASE)
        )
        return patterns.flatMap { pattern ->
            pattern.findAll(text).map { it.groupValues[1] }.toList()
        }.distinct()
    }

    // ==================== Legacy Methods ====================

    override suspend fun generateModifications(
        task: String,
        gatheredContext: GatheredContext,
        projectContext: ProjectContext
    ): Result<List<Modification>, LLMError> = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = DEFAULT_CODING_SYSTEM_PROMPT
            val userPrompt = buildCodingUserPrompt(task, gatheredContext, projectContext)
            log("[LangChainLLMService] Coding phase started")

            val response = chatModel.chat(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
            val content = response.aiMessage().text()
            val modifications = parseModifications(content)
            log("[LangChainLLMService] Parsed ${modifications.size} modification(s)")

            Result.Success(modifications)
        } catch (e: Exception) {
            log("[LangChainLLMService] Coding error: ${e.message}")
            Result.Failure(LLMError.NetworkError(e.message ?: "Code generation failed"))
        }
    }

    override suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): Result<List<Modification>, LLMError> = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = DEFAULT_CODING_SYSTEM_PROMPT
            val userPrompt = buildModificationUserPrompt(instruction, context)
            log("[LangChainLLMService] Provider: ${config.providerType}, Model: ${config.modelId}")

            val response = chatModel.chat(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
            val content = response.aiMessage().text()
            val modifications = parseModifications(content)
            log("[LangChainLLMService] Parsed ${modifications.size} modification(s)")

            Result.Success(modifications)
        } catch (e: Exception) {
            log("[LangChainLLMService] Error: ${e.message}")
            Result.Failure(LLMError.NetworkError(e.message ?: "Unknown error"))
        }
    }

    override suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): Result<AnalysisResponse, LLMError> = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = "You are an expert code analyst. Analyze the provided code and answer questions about it. Be concise but thorough."
            val userPrompt = buildString {
                appendLine("Question: $question\n")
                codeElements.forEach { element ->
                    appendLine("--- ${element.kind}: ${element.name} ---\n${element.content}\n")
                }
            }
            val response = chatModel.chat(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
            val answer = response.aiMessage().text()
            Result.Success(AnalysisResponse(answer = answer, suggestions = extractSuggestions(answer), referencedPaths = emptyList()))
        } catch (e: Exception) {
            Result.Failure(LLMError.NetworkError(e.message ?: "Analysis failed"))
        }
    }

    // ==================== Prompt Building ====================

    private fun buildPlanningUserPrompt(task: String, projectContext: ProjectContext): String = buildString {
        appendLine("=== TASK ===\n$task\n")
        appendLine("=== PROJECT INFO ===\nName: ${projectContext.name}\nRoot: ${projectContext.rootPath}")
        appendLine("Tech stack: ${projectContext.techStack.language}, ${projectContext.techStack.buildTool ?: "unknown"}")
        if (projectContext.techStack.frameworks.isNotEmpty()) appendLine("Frameworks: ${projectContext.techStack.frameworks.joinToString()}")
        appendLine()
        projectContext.description?.let { appendLine("=== PROJECT DESCRIPTION ===\n${it.take(2000)}\n") }
        projectContext.architecture?.let { appendLine("=== ARCHITECTURE ===\n${it.take(3000)}\n") }
        appendLine("=== FILE TREE ===\n${projectContext.fileTree.toCompactString(maxDepth = 4)}\n")
        appendLine("Based on the task and project structure, which files do I need to see?")
    }

    private fun buildCodingUserPrompt(task: String, gatheredContext: GatheredContext, projectContext: ProjectContext): String = buildString {
        appendLine("=== TASK ===\n$task\n")
        appendLine("=== PROJECT INFO ===\nName: ${projectContext.name}\nLanguage: ${projectContext.techStack.language}\n")
        projectContext.architecture?.let { appendLine("=== ARCHITECTURE NOTES ===\n${it.take(1500)}\n") }
        appendLine("=== RELEVANT CODE FILES ===")
        gatheredContext.files.forEach { (path, content) -> appendLine("--- FILE: $path ---\n$content\n") }
        appendLine("Now generate the JSON with modifications to complete the task.")
    }

    private fun buildModificationUserPrompt(instruction: String, context: LLMContext): String = buildString {
        appendLine("=== USER INSTRUCTION ===\n$instruction\n")
        context.projectInfo?.let { appendLine("=== PROJECT INFO ===\nName: ${it.name}\nLanguage: ${it.language}\n") }
        if (context.relevantCode.isNotEmpty()) {
            appendLine("=== EXISTING CODE CONTEXT ===")
            context.relevantCode.forEach { element -> appendLine("--- ${element.kind}: ${element.name} (${element.path.value}) ---\n${element.content}\n") }
        } else {
            appendLine("=== NO EXISTING CODE ===\nCreate new file(s) as needed.\n")
        }
        context.additionalInstructions?.let { appendLine("=== ADDITIONAL INSTRUCTIONS ===\n$it\n") }
        appendLine("Now generate the JSON with modifications.")
    }

    // ==================== Response Parsing (for legacy methods) ====================

    private fun parseModifications(response: String): List<Modification> {
        return try {
            val jsonContent = extractJson(response)
            val jsonObject = Json.parseToJsonElement(jsonContent).jsonObject
            val modificationsArray = jsonObject["modifications"]?.jsonArray ?: return emptyList()
            modificationsArray.mapNotNull { parseModificationElement(it.jsonObject) }
        } catch (e: Exception) {
            log("[LangChainLLMService] JSON parse failed: ${e.message}")
            extractCodeFromMarkdown(response)
        }
    }

    private fun extractJson(response: String): String {
        var cleaned = response.trim()
        if (cleaned.startsWith("```json")) cleaned = cleaned.removePrefix("```json").removeSuffix("```").trim()
        else if (cleaned.startsWith("```")) cleaned = cleaned.removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
    }

    private fun parseModificationElement(json: JsonObject): Modification? {
        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val path = json["path"]?.jsonPrimitive?.contentOrNull ?: return null
        val content = json["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val elementKindStr = json["elementKind"]?.jsonPrimitive?.contentOrNull ?: "FILE"
        val positionStr = json["position"]?.jsonPrimitive?.contentOrNull ?: "LAST_CHILD"
        val elementPath = ElementPath(path)
        val elementKind = try { ElementKind.valueOf(elementKindStr.uppercase()) } catch (e: Exception) { ElementKind.FILE }
        val position = try { InsertPosition.valueOf(positionStr.uppercase()) } catch (e: Exception) { InsertPosition.LAST_CHILD }

        return when (type.uppercase()) {
            "CREATE_FILE" -> Modification.CreateFile(targetPath = elementPath, content = content)
            "REPLACE_FILE" -> Modification.ReplaceFile(targetPath = elementPath, newContent = content)
            "DELETE_FILE" -> Modification.DeleteFile(targetPath = elementPath)
            "CREATE_ELEMENT" -> Modification.CreateElement(targetPath = elementPath, elementKind = elementKind, content = content, position = position)
            "REPLACE_ELEMENT" -> Modification.ReplaceElement(targetPath = elementPath, newContent = content)
            "DELETE_ELEMENT" -> Modification.DeleteElement(targetPath = elementPath)
            else -> null
        }
    }

    private fun extractCodeFromMarkdown(response: String): List<Modification> {
        return Regex("```(?:kotlin|java)?\\s*\\n([\\s\\S]*?)```").findAll(response).mapNotNull { match ->
            val code = match.groupValues[1].trim()
            if (code.isNotBlank()) {
                val fileName = extractFileNameFromCode(code)
                Modification.CreateFile(targetPath = ElementPath("src/main/kotlin/$fileName"), content = code)
            } else null
        }.toList()
    }

    private fun extractFileNameFromCode(code: String): String {
        Regex("(?:class|object|interface|enum class)\\s+(\\w+)").find(code)?.let { return "${it.groupValues[1]}.kt" }
        Regex("package\\s+([\\w.]+)").find(code)?.let { return "${it.groupValues[1].split(".").last().replaceFirstChar { c -> c.uppercase() }}Main.kt" }
        return "GeneratedCode.kt"
    }

    private fun extractSuggestions(text: String): List<String> {
        return text.lines().map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("*") }
            .map { it.removePrefix("-").removePrefix("*").trim() }
            .filter { it.length in 10..200 }.take(5)
    }

    private fun parsePlanningResponse(response: String): ContextRequest {
        return try {
            val jsonContent = extractJson(response)
            val jsonObject = Json.parseToJsonElement(jsonContent).jsonObject
            val files = jsonObject["requestedFiles"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            ContextRequest(requestedFiles = files, reasoning = jsonObject["reasoning"]?.jsonPrimitive?.contentOrNull)
        } catch (e: Exception) {
            ContextRequest(requestedFiles = Regex("""[\w/]+\.kt""").findAll(response).map { it.value }.distinct().toList(), reasoning = null)
        }
    }

    // ==================== Info ====================

    fun getProviderInfo(): String = "${config.providerType} / ${config.modelId}"

    // ==================== Default Prompts ====================

    companion object {
        private val DEFAULT_CHAT_SYSTEM_PROMPT = """
            You are MaxVibes, an AI coding assistant integrated into IntelliJ IDEA. You help developers write and modify Kotlin code.
            
            PROJECT: {{projectName}}
            LANGUAGE: {{language}}
            
            HOW TO RESPOND:
            1. First, explain what you're going to do in plain language
            2. Describe what changes you made (files created, functions added, etc.)
            3. If you need to create or modify code, include a JSON block at the END of your response
            
            RESPONSE FORMAT:
            - Write naturally, like a helpful colleague
            - Be concise but informative
            - After your explanation, if there are code changes, add:
            ```json
            {
                "modifications": [
                    {
                        "type": "CREATE_FILE" | "REPLACE_FILE",
                        "path": "src/main/kotlin/com/example/File.kt",
                        "content": "full file content"
                    }
                ]
            }
            ```
            
            RULES FOR CODE:
            - Always include package declaration and imports
            - Write clean, idiomatic Kotlin
            - Follow existing project patterns
            - For new files: use CREATE_FILE
            - For changing existing files: use REPLACE_FILE with complete new content
            
            If the user just asks a question or wants to chat, respond normally without JSON.
            If the user asks to modify code, explain what you'll do, then include the JSON.
        """.trimIndent()

        private val DEFAULT_PLANNING_SYSTEM_PROMPT = """
            You are an expert software architect analyzing a codebase to understand what files are needed for a task.
            Your job is to look at the project structure and determine which files the developer needs to see to complete the given task.
            CRITICAL: Respond ONLY with a valid JSON object. No markdown, no explanations, just JSON.
            Response format: {"requestedFiles": ["path/to/file1.kt"], "reasoning": "explanation"}
        """.trimIndent()

        private val DEFAULT_CODING_SYSTEM_PROMPT = """
            You are an expert Kotlin developer. Your task is to generate code modifications based on user instructions.
            CRITICAL: Respond ONLY with a valid JSON object. No markdown, no explanations, just JSON.
            Response format:
            {
                "modifications": [
                    {
                        "type": "CREATE_FILE" | "REPLACE_FILE" | "CREATE_ELEMENT" | "REPLACE_ELEMENT" | "DELETE_ELEMENT",
                        "path": "src/main/kotlin/com/example/FileName.kt",
                        "elementKind": "FILE" | "CLASS" | "FUNCTION" | "PROPERTY",
                        "content": "full code content here",
                        "position": "LAST_CHILD"
                    }
                ]
            }
            Rules:
            1. For new files: use "CREATE_FILE" with complete file content including package and imports
            2. For modifying existing code: use "REPLACE_FILE" or "REPLACE_ELEMENT"
            3. Content must be complete, compilable Kotlin code
        """.trimIndent()
    }
}