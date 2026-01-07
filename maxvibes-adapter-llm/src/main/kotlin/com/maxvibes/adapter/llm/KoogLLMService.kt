// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/KoogLLMService.kt
package com.maxvibes.adapter.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OllamaModels
import com.maxvibes.adapter.llm.agent.AnalyzerAgent
import com.maxvibes.adapter.llm.agent.CoderAgent
import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.shared.result.Result
import io.ktor.client.plugins.*

/**
 * Реализация LLMService через Koog framework.
 * Поддерживает OpenAI, Anthropic и Ollama провайдеры.
 */
class KoogLLMService(
    private val config: LLMProviderConfig
) : LLMService {

    private val promptExecutor: PromptExecutor by lazy { createPromptExecutor() }

    private val llmModel: LLModel by lazy { createLLMModel() }

    private val coderAgent: CoderAgent by lazy {
        CoderAgent(promptExecutor, llmModel, config.temperature)
    }

    private val analyzerAgent: AnalyzerAgent by lazy {
        AnalyzerAgent(promptExecutor, llmModel, config.temperature)
    }

    override suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): Result<List<Modification>, LLMError> {
        return try {
            log("generateModifications: instruction='${instruction.take(50)}...', provider=${config.providerType}, model=${config.modelId}")
            validateConfig()

            log("Calling coderAgent.generateModifications...")
            val modifications = coderAgent.generateModifications(instruction, context)
            log("Got ${modifications.size} modifications")

            if (modifications.isEmpty()) {
                Result.Failure(
                    LLMError.InvalidResponse("No modifications generated. Try rephrasing your instruction.")
                )
            } else {
                Result.Success(modifications)
            }
        } catch (e: IllegalStateException) {
            logError("ConfigurationError", e)
            Result.Failure(LLMError.ConfigurationError(e.message ?: "Configuration error"))
        } catch (e: HttpRequestTimeoutException) {
            logError("HttpRequestTimeoutException", e)
            Result.Failure(LLMError.NetworkError("Request timeout (${TIMEOUT_MS/1000}s). The LLM is taking too long. Try a simpler instruction or check your connection."))
        } catch (e: java.net.ConnectException) {
            logError("ConnectException", e)
            Result.Failure(LLMError.NetworkError("Cannot connect to LLM provider: ${e.message}"))
        } catch (e: java.net.SocketTimeoutException) {
            logError("SocketTimeoutException", e)
            Result.Failure(LLMError.NetworkError("Connection timeout: ${e.message}"))
        } catch (e: Exception) {
            logError("Exception", e)
            when {
                e.message?.contains("timeout", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.NetworkError("Timeout: ${e.message}"))
                }
                e.message?.contains("rate limit", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.RateLimitError())
                }
                e.message?.contains("unauthorized", ignoreCase = true) == true ||
                        e.message?.contains("invalid api key", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.ConfigurationError("Invalid API key"))
                }
                else -> {
                    Result.Failure(LLMError.InvalidResponse("LLM error: ${e::class.simpleName}: ${e.message}"))
                }
            }
        }
    }

    override suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): Result<AnalysisResponse, LLMError> {
        return try {
            log("analyzeCode: question='${question.take(50)}...', elements=${codeElements.size}")
            validateConfig()

            log("Calling analyzerAgent.analyzeCode...")
            val response = analyzerAgent.analyzeCode(question, codeElements)
            log("Got analysis response")

            Result.Success(response)
        } catch (e: IllegalStateException) {
            logError("ConfigurationError", e)
            Result.Failure(LLMError.ConfigurationError(e.message ?: "Configuration error"))
        } catch (e: HttpRequestTimeoutException) {
            logError("HttpRequestTimeoutException", e)
            Result.Failure(LLMError.NetworkError("Request timeout. The LLM is taking too long."))
        } catch (e: java.net.ConnectException) {
            logError("ConnectException", e)
            Result.Failure(LLMError.NetworkError("Cannot connect to LLM provider: ${e.message}"))
        } catch (e: java.net.SocketTimeoutException) {
            logError("SocketTimeoutException", e)
            Result.Failure(LLMError.NetworkError("Connection timeout: ${e.message}"))
        } catch (e: Exception) {
            logError("Exception", e)
            when {
                e.message?.contains("timeout", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.NetworkError("Timeout: ${e.message}"))
                }
                e.message?.contains("rate limit", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.RateLimitError())
                }
                e.message?.contains("unauthorized", ignoreCase = true) == true ||
                        e.message?.contains("invalid api key", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.ConfigurationError("Invalid API key"))
                }
                else -> {
                    Result.Failure(LLMError.InvalidResponse("Analysis error: ${e::class.simpleName}: ${e.message}"))
                }
            }
        }
    }

    private fun validateConfig() {
        log("Validating config: provider=${config.providerType}, apiKey=${config.apiKey.take(10)}..., model=${config.modelId}")
        when (config.providerType) {
            LLMProviderType.OPENAI, LLMProviderType.ANTHROPIC -> {
                if (config.apiKey.isBlank()) {
                    throw IllegalStateException("API key is required for ${config.providerType}")
                }
            }
            LLMProviderType.OLLAMA -> {
                if (config.baseUrl.isNullOrBlank()) {
                    throw IllegalStateException("Base URL is required for Ollama")
                }
            }
        }
    }

    private fun createPromptExecutor(): PromptExecutor {
        log("Creating PromptExecutor for ${config.providerType}")
        return when (config.providerType) {
            LLMProviderType.OPENAI -> {
                log("Creating OpenAI executor with key: ${config.apiKey.take(10)}...")
                simpleOpenAIExecutor(config.apiKey)
            }
            LLMProviderType.ANTHROPIC -> {
                log("Creating Anthropic executor")
                simpleAnthropicExecutor(config.apiKey)
            }
            LLMProviderType.OLLAMA -> {
                log("Creating Ollama executor at ${config.baseUrl}")
                simpleOllamaAIExecutor(config.baseUrl ?: "http://localhost:11434")
            }
        }
    }

    private fun createLLMModel(): LLModel {
        val model = when (config.providerType) {
            LLMProviderType.OPENAI -> resolveOpenAIModel(config.modelId)
            LLMProviderType.ANTHROPIC -> resolveAnthropicModel(config.modelId)
            LLMProviderType.OLLAMA -> resolveOllamaModel(config.modelId)
        }
        log("Created LLM model: ${model.id}")
        return model
    }

    /**
     * Возвращает информацию о текущем провайдере
     */
    fun getProviderInfo(): String {
        return "${config.providerType.name} / ${config.modelId}"
    }

    companion object {
        private const val TAG = "KoogLLMService"
        private const val TIMEOUT_MS = 120_000L // 2 minutes

        private fun log(message: String) {
            println("[$TAG] $message")
        }

        private fun logError(type: String, e: Exception) {
            println("[$TAG] ERROR $type: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }
}
/**
 * Normalize model id
 */
private fun normalizeModelId(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace('_', '-')
        .replace(Regex("\\s+"), "")
}

/**
 * Resolve OpenAI model by ID
 */
private fun resolveOpenAIModel(modelId: String): LLModel {
    val id = normalizeModelId(modelId)
    println("[KoogLLMService] Resolving OpenAI model: '$modelId' -> normalized: '$id'")

    return when (id) {
        "gpt-5.2", "gpt-5-2", "gpt5.2", "gpt5-2", "chatgpt-5.2", "chatgpt5.2" ->
            OpenAIModels.Chat.GPT5_2

        "gpt-5.2-pro", "gpt-5-2-pro", "gpt5.2-pro", "gpt5-2-pro", "gpt-5.2pro", "gpt5.2pro" ->
            OpenAIModels.Chat.GPT5_2Pro

        "gpt-5.1", "gpt-5-1", "gpt5.1", "gpt5-1" ->
            OpenAIModels.Chat.GPT5_1

        "gpt-5", "gpt5" ->
            OpenAIModels.Chat.GPT5
        "gpt-5-mini", "gpt5-mini", "gpt-5mini", "gpt5mini" ->
            OpenAIModels.Chat.GPT5Mini
        "gpt-5-nano", "gpt5-nano", "gpt-5nano", "gpt5nano" ->
            OpenAIModels.Chat.GPT5Nano

        "gpt-5-codex", "gpt5-codex", "gpt-5codex", "gpt5codex" ->
            OpenAIModels.Chat.GPT5Codex

        "gpt-4o", "gpt4o" ->
            OpenAIModels.Chat.GPT4o

        "gpt-4o-mini", "gpt4o-mini", "gpt-4omini", "gpt4omini" ->
            OpenAIModels.Chat.GPT4oMini

        "gpt-4.1", "gpt-4-1", "gpt4.1", "gpt4-1" ->
            OpenAIModels.Chat.GPT4_1
        "gpt-4.1-mini", "gpt-4-1-mini", "gpt4.1-mini", "gpt4-1-mini" ->
            OpenAIModels.Chat.GPT4_1Mini
        "gpt-4.1-nano", "gpt-4-1-nano", "gpt4.1-nano", "gpt4-1-nano" ->
            OpenAIModels.Chat.GPT4_1Nano

        "o1" -> OpenAIModels.Chat.O1
        "o3" -> OpenAIModels.Chat.O3
        "o3-mini", "o3mini" -> OpenAIModels.Chat.O3Mini
        "o4-mini", "o4mini" -> OpenAIModels.Chat.O4Mini

        else -> {
            println("[KoogLLMService] Unknown model '$id', defaulting to GPT5_2")
            OpenAIModels.Chat.GPT5_2
        }
    }
}

/**
 * Resolve Anthropic model by ID
 */
private fun resolveAnthropicModel(modelId: String): LLModel {
    println("[KoogLLMService] Resolving Anthropic model: '$modelId'")
    return when {
        modelId.contains("opus", ignoreCase = true) -> AnthropicModels.Opus_4_1
        modelId.contains("sonnet", ignoreCase = true) -> AnthropicModels.Sonnet_4_5
        modelId.contains("haiku", ignoreCase = true) -> AnthropicModels.Haiku_3_5
        else -> AnthropicModels.Sonnet_4_5
    }
}

/**
 * Resolve Ollama model by ID
 */
private fun resolveOllamaModel(modelId: String): LLModel {
    println("[KoogLLMService] Resolving Ollama model: '$modelId'")
    val baseModel = OllamaModels.Meta.LLAMA_3_2
    return when {
        modelId.contains("llama3", ignoreCase = true) -> baseModel
        modelId.contains("codellama", ignoreCase = true) -> baseModel.copy(id = "codellama")
        modelId.contains("mistral", ignoreCase = true) -> baseModel.copy(id = "mistral")
        modelId.contains("qwen", ignoreCase = true) -> baseModel.copy(id = modelId)
        else -> baseModel.copy(id = modelId)
    }
}

/**
 * Factory функции
 */
fun createKoogLLMService(config: LLMProviderConfig): KoogLLMService {
    return KoogLLMService(config)
}

fun createOpenAILLMService(
    apiKey: String,
    modelId: String = "gpt-5.2"
): KoogLLMService {
    return KoogLLMService(LLMProviderConfig.openAI(apiKey, modelId))
}

fun createAnthropicLLMService(
    apiKey: String,
    modelId: String = "claude-sonnet-4-20250514"
): KoogLLMService {
    return KoogLLMService(LLMProviderConfig.anthropic(apiKey, modelId))
}

fun createOllamaLLMService(
    modelId: String = "llama3.2",
    baseUrl: String = "http://localhost:11434"
): KoogLLMService {
    return KoogLLMService(LLMProviderConfig.ollama(modelId, baseUrl))
}