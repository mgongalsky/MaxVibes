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

/**
 * Реализация LLMService через Koog framework.
 * Поддерживает OpenAI, Anthropic и Ollama провайдеры.
 *
 * OpenAI: по умолчанию GPT-5.2 (ChatGPT 5.2) — OpenAIModels.Chat.GPT5_2
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
            validateConfig()

            val modifications = coderAgent.generateModifications(instruction, context)

            if (modifications.isEmpty()) {
                Result.Failure(
                    LLMError.InvalidResponse("No modifications generated. Try rephrasing your instruction.")
                )
            } else {
                Result.Success(modifications)
            }
        } catch (e: IllegalStateException) {
            Result.Failure(LLMError.ConfigurationError(e.message ?: "Configuration error"))
        } catch (e: java.net.ConnectException) {
            Result.Failure(LLMError.NetworkError("Cannot connect to LLM provider: ${e.message}"))
        } catch (e: java.net.SocketTimeoutException) {
            Result.Failure(LLMError.NetworkError("Connection timeout: ${e.message}"))
        } catch (e: Exception) {
            when {
                e.message?.contains("rate limit", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.RateLimitError())
                }
                e.message?.contains("unauthorized", ignoreCase = true) == true ||
                        e.message?.contains("invalid api key", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.ConfigurationError("Invalid API key"))
                }
                else -> {
                    Result.Failure(LLMError.InvalidResponse("LLM error: ${e.message}"))
                }
            }
        }
    }

    override suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): Result<AnalysisResponse, LLMError> {
        return try {
            validateConfig()

            val response = analyzerAgent.analyzeCode(question, codeElements)
            Result.Success(response)
        } catch (e: IllegalStateException) {
            Result.Failure(LLMError.ConfigurationError(e.message ?: "Configuration error"))
        } catch (e: java.net.ConnectException) {
            Result.Failure(LLMError.NetworkError("Cannot connect to LLM provider: ${e.message}"))
        } catch (e: java.net.SocketTimeoutException) {
            Result.Failure(LLMError.NetworkError("Connection timeout: ${e.message}"))
        } catch (e: Exception) {
            when {
                e.message?.contains("rate limit", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.RateLimitError())
                }
                e.message?.contains("unauthorized", ignoreCase = true) == true ||
                        e.message?.contains("invalid api key", ignoreCase = true) == true -> {
                    Result.Failure(LLMError.ConfigurationError("Invalid API key"))
                }
                else -> {
                    Result.Failure(LLMError.InvalidResponse("Analysis error: ${e.message}"))
                }
            }
        }
    }

    private fun validateConfig() {
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
        return when (config.providerType) {
            LLMProviderType.OPENAI -> simpleOpenAIExecutor(config.apiKey)
            LLMProviderType.ANTHROPIC -> simpleAnthropicExecutor(config.apiKey)
            LLMProviderType.OLLAMA -> simpleOllamaAIExecutor(config.baseUrl ?: "http://localhost:11434")
        }
    }

    private fun createLLMModel(): LLModel {
        return when (config.providerType) {
            LLMProviderType.OPENAI -> resolveOpenAIModel(config.modelId)
            LLMProviderType.ANTHROPIC -> resolveAnthropicModel(config.modelId)
            LLMProviderType.OLLAMA -> resolveOllamaModel(config.modelId)
        }
    }

    /**
     * Возвращает информацию о текущем провайдере
     */
    fun getProviderInfo(): String {
        return "${config.providerType.name} / ${config.modelId}"
    }
}

/**
 * Normalize model id:
 * - lowercase
 * - '_' -> '-'
 * - remove whitespace
 */
private fun normalizeModelId(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace('_', '-')
        .replace(Regex("\\s+"), "")
}

/**
 * Resolve OpenAI model by ID
 *
 * Поддерживает алиасы вроде:
 * - "gpt-5.2", "gpt-5-2", "gpt5.2", "chatgpt-5.2" -> GPT5_2
 * - "gpt-5.2-pro", "gpt-5-2-pro", "gpt5.2-pro" -> GPT5_2Pro
 */
private fun resolveOpenAIModel(modelId: String): LLModel {
    val id = normalizeModelId(modelId)

    return when (id) {
        // GPT-5.2 (ChatGPT 5.2)
        "gpt-5.2", "gpt-5-2", "gpt5.2", "gpt5-2", "chatgpt-5.2", "chatgpt5.2" ->
            OpenAIModels.Chat.GPT5_2

        // GPT-5.2 Pro
        "gpt-5.2-pro", "gpt-5-2-pro", "gpt5.2-pro", "gpt5-2-pro", "gpt-5.2pro", "gpt5.2pro" ->
            OpenAIModels.Chat.GPT5_2Pro

        // GPT-5.1
        "gpt-5.1", "gpt-5-1", "gpt5.1", "gpt5-1" ->
            OpenAIModels.Chat.GPT5_1

        // GPT-5 family
        "gpt-5", "gpt5" ->
            OpenAIModels.Chat.GPT5
        "gpt-5-mini", "gpt5-mini", "gpt-5mini", "gpt5mini" ->
            OpenAIModels.Chat.GPT5Mini
        "gpt-5-nano", "gpt5-nano", "gpt-5nano", "gpt5nano" ->
            OpenAIModels.Chat.GPT5Nano

        // Codex variants (Koog: Responses-only, но даём возможность явно выбрать)
        "gpt-5-codex", "gpt5-codex", "gpt-5codex", "gpt5codex" ->
            OpenAIModels.Chat.GPT5Codex

        // 4o / 4.1 family
        "gpt-4o", "gpt4o" ->
            OpenAIModels.Chat.GPT4o

        // ВАЖНО: GPT-4o mini находится в Chat, не в Reasoning
        "gpt-4o-mini", "gpt4o-mini", "gpt-4omini", "gpt4omini" ->
            OpenAIModels.Chat.GPT4oMini

        "gpt-4.1", "gpt-4-1", "gpt4.1", "gpt4-1" ->
            OpenAIModels.Chat.GPT4_1
        "gpt-4.1-mini", "gpt-4-1-mini", "gpt4.1-mini", "gpt4-1-mini" ->
            OpenAIModels.Chat.GPT4_1Mini
        "gpt-4.1-nano", "gpt-4-1-nano", "gpt4.1-nano", "gpt4-1-nano" ->
            OpenAIModels.Chat.GPT4_1Nano

        // o-series
        "o1" -> OpenAIModels.Chat.O1
        "o3" -> OpenAIModels.Chat.O3
        "o3-mini", "o3mini" -> OpenAIModels.Chat.O3Mini
        "o4-mini", "o4mini" -> OpenAIModels.Chat.O4Mini

        else ->
            // Default: GPT-5.2
            OpenAIModels.Chat.GPT5_2
    }
}

/**
 * Resolve Anthropic model by ID
 */
private fun resolveAnthropicModel(modelId: String): LLModel {
    return when {
        modelId.contains("opus", ignoreCase = true) -> AnthropicModels.Opus_4_1
        modelId.contains("sonnet", ignoreCase = true) -> AnthropicModels.Sonnet_4_5
        modelId.contains("haiku", ignoreCase = true) -> AnthropicModels.Haiku_3_5
        else -> AnthropicModels.Sonnet_4_5
    }
}

/**
 * Resolve Ollama model by ID
 * Uses copy() for custom model IDs since Ollama can run any model
 */
private fun resolveOllamaModel(modelId: String): LLModel {
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
 * Factory функция для создания KoogLLMService
 */
fun createKoogLLMService(config: LLMProviderConfig): KoogLLMService {
    return KoogLLMService(config)
}

/**
 * Factory функция для создания KoogLLMService с OpenAI
 *
 * По умолчанию — GPT-5.2
 */
fun createOpenAILLMService(
    apiKey: String,
    modelId: String = "gpt-5.2"
): KoogLLMService {
    return KoogLLMService(LLMProviderConfig.openAI(apiKey, modelId))
}

/**
 * Factory функция для создания KoogLLMService с Anthropic
 */
fun createAnthropicLLMService(
    apiKey: String,
    modelId: String = "claude-sonnet-4-20250514"
): KoogLLMService {
    return KoogLLMService(LLMProviderConfig.anthropic(apiKey, modelId))
}

/**
 * Factory функция для создания KoogLLMService с Ollama
 */
fun createOllamaLLMService(
    modelId: String = "llama3.2",
    baseUrl: String = "http://localhost:11434"
): KoogLLMService {
    return KoogLLMService(LLMProviderConfig.ollama(modelId, baseUrl))
}
