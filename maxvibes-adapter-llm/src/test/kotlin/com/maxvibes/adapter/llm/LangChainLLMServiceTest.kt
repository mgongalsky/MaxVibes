package com.maxvibes.adapter.llm

import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LangChainLLMServiceTest {

    @Test
    fun `should create service with OpenAI config`() {
        val config = LLMProviderConfig(
            providerType = LLMProviderType.OPENAI,
            apiKey = "test-key",
            modelId = "gpt-4o"
        )

        val service = LangChainLLMService(config)

        assertEquals("OPENAI / gpt-4o", service.getProviderInfo())
    }

    @Test
    fun `should create service with Anthropic config`() {
        val config = LLMProviderConfig(
            providerType = LLMProviderType.ANTHROPIC,
            apiKey = "test-key",
            modelId = "claude-sonnet-4"
        )

        val service = LangChainLLMService(config)

        assertEquals("ANTHROPIC / claude-sonnet-4", service.getProviderInfo())
    }

    @Test
    fun `should create service with Ollama config`() {
        val config = LLMProviderConfig(
            providerType = LLMProviderType.OLLAMA,
            apiKey = "",
            modelId = "llama3.2",
            baseUrl = "http://localhost:11434"
        )

        val service = LangChainLLMService(config)

        assertEquals("OLLAMA / llama3.2", service.getProviderInfo())
    }

    @Test
    fun `factory should create from config`() {
        val config = LLMProviderConfig(
            providerType = LLMProviderType.OPENAI,
            apiKey = "test-key",
            modelId = "gpt-4o-mini"
        )

        val service = LLMServiceFactory.create(config)

        assertTrue(service is LangChainLLMService)
        assertEquals("OPENAI / gpt-4o-mini", service.getProviderInfo())
    }
}