// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/LLMServiceFactory.kt
package com.maxvibes.adapter.llm

import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import com.maxvibes.application.port.output.LLMService

/**
 * Factory для создания LLMService.
 * Позволяет легко переключаться между провайдерами.
 */
object LLMServiceFactory {

    /**
     * Создаёт LLMService на основе доступных API ключей из environment variables.
     * Приоритет: ANTHROPIC -> OPENAI -> OLLAMA (если запущен)
     *
     * @return KoogLLMService или null если ни один провайдер не настроен
     */
    fun createFromEnvironment(): KoogLLMService? {
        // Проверяем Anthropic
        val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
        if (!anthropicKey.isNullOrBlank()) {
            return createAnthropicLLMService(anthropicKey)
        }

        // Проверяем OpenAI
        val openaiKey = System.getenv("OPENAI_API_KEY")
        if (!openaiKey.isNullOrBlank()) {
            return createOpenAILLMService(openaiKey)
        }

        // Проверяем Ollama (по умолчанию на localhost)
        val ollamaUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434"
        if (isOllamaAvailable(ollamaUrl)) {
            val modelId = System.getenv("OLLAMA_MODEL") ?: "llama3.2"
            return createOllamaLLMService(modelId, ollamaUrl)
        }

        return null
    }

    /**
     * Создаёт LLMService на основе конфигурации.
     */
    fun create(config: LLMProviderConfig): KoogLLMService {
        return KoogLLMService(config)
    }

    /**
     * Создаёт LLMService с OpenAI.
     */
    fun createOpenAI(
        apiKey: String,
        modelId: String = "gpt-4o"
    ): KoogLLMService {
        return createOpenAILLMService(apiKey, modelId)
    }

    /**
     * Создаёт LLMService с Anthropic Claude.
     */
    fun createAnthropic(
        apiKey: String,
        modelId: String = "claude-sonnet-4-20250514"
    ): KoogLLMService {
        return createAnthropicLLMService(apiKey, modelId)
    }

    /**
     * Создаёт LLMService с локальным Ollama.
     */
    fun createOllama(
        modelId: String = "llama3.2",
        baseUrl: String = "http://localhost:11434"
    ): KoogLLMService {
        return createOllamaLLMService(modelId, baseUrl)
    }

    /**
     * Проверяет доступность Ollama сервера.
     */
    private fun isOllamaAvailable(baseUrl: String): Boolean {
        return try {
            val url = java.net.URL("$baseUrl/api/tags")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.requestMethod = "GET"
            connection.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Возвращает список доступных провайдеров на основе environment.
     */
    fun getAvailableProviders(): List<AvailableProvider> {
        val providers = mutableListOf<AvailableProvider>()

        val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
        if (!anthropicKey.isNullOrBlank()) {
            providers.add(AvailableProvider(
                type = LLMProviderType.ANTHROPIC,
                name = "Anthropic Claude",
                configured = true
            ))
        }

        val openaiKey = System.getenv("OPENAI_API_KEY")
        if (!openaiKey.isNullOrBlank()) {
            providers.add(AvailableProvider(
                type = LLMProviderType.OPENAI,
                name = "OpenAI GPT",
                configured = true
            ))
        }

        val ollamaUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434"
        providers.add(AvailableProvider(
            type = LLMProviderType.OLLAMA,
            name = "Ollama (Local)",
            configured = isOllamaAvailable(ollamaUrl)
        ))

        return providers
    }
}

/**
 * Информация о доступном провайдере
 */
data class AvailableProvider(
    val type: LLMProviderType,
    val name: String,
    val configured: Boolean
)

/**
 * Результат проверки настроек LLM
 */
sealed class LLMConfigurationStatus {
    data class Configured(
        val provider: LLMProviderType,
        val modelId: String
    ) : LLMConfigurationStatus()

    data class NotConfigured(
        val message: String = "No LLM provider configured. Set ANTHROPIC_API_KEY or OPENAI_API_KEY environment variable."
    ) : LLMConfigurationStatus()
}

/**
 * Extension function для проверки статуса конфигурации
 */
fun LLMServiceFactory.checkConfiguration(): LLMConfigurationStatus {
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
    if (!anthropicKey.isNullOrBlank()) {
        return LLMConfigurationStatus.Configured(
            provider = LLMProviderType.ANTHROPIC,
            modelId = "claude-sonnet-4-20250514"
        )
    }

    val openaiKey = System.getenv("OPENAI_API_KEY")
    if (!openaiKey.isNullOrBlank()) {
        return LLMConfigurationStatus.Configured(
            provider = LLMProviderType.OPENAI,
            modelId = "gpt-4o"
        )
    }

    return LLMConfigurationStatus.NotConfigured()
}