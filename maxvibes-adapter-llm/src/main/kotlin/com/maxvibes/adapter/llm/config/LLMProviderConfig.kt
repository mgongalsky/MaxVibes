package com.maxvibes.adapter.llm.config

/**
 * Поддерживаемые LLM провайдеры
 */
enum class LLMProviderType {
    OPENAI,
    ANTHROPIC,
    OLLAMA,
    /** DeepSeek — OpenAI-compatible API, очень дешёвый ($0.14/M tokens) */
    DEEPSEEK
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
            apiKey = "",
            modelId = modelId,
            baseUrl = baseUrl
        )

        /**
         * Дефолтная конфигурация для DeepSeek (OpenAI-compatible, дешёвый)
         */
        fun deepSeek(
            apiKey: String,
            modelId: String = "deepseek-chat"
        ) = LLMProviderConfig(
            providerType = LLMProviderType.DEEPSEEK,
            apiKey = apiKey,
            modelId = modelId,
            baseUrl = "https://api.deepseek.com"
        )
    }
}