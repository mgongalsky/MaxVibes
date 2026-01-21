// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/LLMServiceFactory.kt
package com.maxvibes.adapter.llm

import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import com.maxvibes.application.port.output.LLMService

/**
 * Factory for creating LLMService instances based on configuration.
 * Supports OpenAI, Anthropic, and Ollama providers via LangChain4j.
 */
object LLMServiceFactory {

    /**
     * Creates an LLMService instance based on the provided configuration.
     *
     * @param config The LLM provider configuration
     * @return An LLMService implementation
     */
    fun create(config: LLMProviderConfig): LangChainLLMService {
        return LangChainLLMService(config)
    }

    /**
     * Creates an LLMService from environment variables.
     * Checks for API keys in order: ANTHROPIC_API_KEY, OPENAI_API_KEY
     * Falls back to Ollama if no API keys found.
     *
     * @return An LLMService implementation
     */
    fun createFromEnvironment(): LangChainLLMService {
        // Check for Anthropic API key
        val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
        if (!anthropicKey.isNullOrBlank()) {
            return create(
                LLMProviderConfig(
                    providerType = LLMProviderType.ANTHROPIC,
                    apiKey = anthropicKey,
                    modelId = "claude-sonnet-4-20250514"
                )
            )
        }

        // Check for OpenAI API key
        val openaiKey = System.getenv("OPENAI_API_KEY")
        if (!openaiKey.isNullOrBlank()) {
            return create(
                LLMProviderConfig(
                    providerType = LLMProviderType.OPENAI,
                    apiKey = openaiKey,
                    modelId = "gpt-4o"
                )
            )
        }

        // Fallback to Ollama (local)
        return create(
            LLMProviderConfig(
                providerType = LLMProviderType.OLLAMA,
                apiKey = "",
                modelId = "llama3.2",
                baseUrl = "http://localhost:11434"
            )
        )
    }
}