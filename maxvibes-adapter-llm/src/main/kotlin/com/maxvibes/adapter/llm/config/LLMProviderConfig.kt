// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/config/LLMProviderConfig.kt
package com.maxvibes.adapter.llm.config

/**
 * Поддерживаемые LLM провайдеры
 */
enum class LLMProviderType {
    OPENAI,
    ANTHROPIC,
    OLLAMA
}

/**
 * Конфигурация LLM провайдера
 */
data class LLMProviderConfig(
    val providerType: LLMProviderType,
    val apiKey: String,
    val modelId: String,
    val baseUrl: String? = null,
    val temperature: Double = 0.2,
    val maxTokens: Int = 32768
) {
    companion object {
        /**
         * Дефолтная конфигурация для OpenAI
         */
        fun openAI(
            apiKey: String,
            modelId: String = "gpt-4o"
        ) = LLMProviderConfig(
            providerType = LLMProviderType.OPENAI,
            apiKey = apiKey,
            modelId = modelId
        )

        /**
         * Дефолтная конфигурация для Anthropic
         */
        fun anthropic(
            apiKey: String,
            modelId: String = "claude-sonnet-4-20250514"
        ) = LLMProviderConfig(
            providerType = LLMProviderType.ANTHROPIC,
            apiKey = apiKey,
            modelId = modelId
        )

        /**
         * Дефолтная конфигурация для Ollama (локальный)
         */
        fun ollama(
            modelId: String = "llama3.2",
            baseUrl: String = "http://localhost:11434"
        ) = LLMProviderConfig(
            providerType = LLMProviderType.OLLAMA,
            apiKey = "", // Ollama не требует API key
            modelId = modelId,
            baseUrl = baseUrl
        )
    }
}