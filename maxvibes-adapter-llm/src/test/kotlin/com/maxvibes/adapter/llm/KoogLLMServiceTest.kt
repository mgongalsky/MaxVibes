// maxvibes-adapter-llm/src/test/kotlin/com/maxvibes/adapter/llm/KoogLLMServiceTest.kt
package com.maxvibes.adapter.llm

import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KoogLLMServiceTest {

    @Test
    fun `createOpenAILLMService should create service with OpenAI config`() {
        val apiKey = "test-api-key"
        val modelId = "gpt-4o"

        // We can't actually call the LLM without a real key,
        // but we can verify the service is created correctly
        val service = createOpenAILLMService(apiKey, modelId)

        assertNotNull(service)
        assertEquals("OPENAI / gpt-4o", service.getProviderInfo())
    }

    @Test
    fun `createAnthropicLLMService should create service with Anthropic config`() {
        val apiKey = "test-api-key"
        val modelId = "claude-sonnet-4-20250514"

        val service = createAnthropicLLMService(apiKey, modelId)

        assertNotNull(service)
        assertEquals("ANTHROPIC / claude-sonnet-4-20250514", service.getProviderInfo())
    }

    @Test
    fun `createOllamaLLMService should create service with Ollama config`() {
        val modelId = "llama3.2"
        val baseUrl = "http://localhost:11434"

        val service = createOllamaLLMService(modelId, baseUrl)

        assertNotNull(service)
        assertEquals("OLLAMA / llama3.2", service.getProviderInfo())
    }

    @Test
    fun `LLMProviderConfig openAI factory should use defaults`() {
        val config = LLMProviderConfig.openAI("test-key")

        assertEquals(LLMProviderType.OPENAI, config.providerType)
        assertEquals("test-key", config.apiKey)
        assertEquals("gpt-4o", config.modelId)
    }

    @Test
    fun `LLMProviderConfig anthropic factory should use defaults`() {
        val config = LLMProviderConfig.anthropic("test-key")

        assertEquals(LLMProviderType.ANTHROPIC, config.providerType)
        assertEquals("test-key", config.apiKey)
        assertEquals("claude-sonnet-4-20250514", config.modelId)
    }

    @Test
    fun `LLMProviderConfig ollama factory should use defaults`() {
        val config = LLMProviderConfig.ollama()

        assertEquals(LLMProviderType.OLLAMA, config.providerType)
        assertEquals("", config.apiKey) // Ollama doesn't require API key
        assertEquals("llama3.2", config.modelId)
        assertEquals("http://localhost:11434", config.baseUrl)
    }

    @Test
    fun `LLMProviderConfig should allow custom model`() {
        val config = LLMProviderConfig.openAI(
            apiKey = "key",
            modelId = "gpt-4-turbo"
        )

        assertEquals("gpt-4-turbo", config.modelId)
    }

    @Test
    fun `LLMProviderConfig default temperature should be 0_2`() {
        val config = LLMProviderConfig.openAI("key")

        assertEquals(0.2, config.temperature)
    }

    @Test
    fun `LLMProviderConfig default maxTokens should be 4096`() {
        val config = LLMProviderConfig.openAI("key")

        assertEquals(4096, config.maxTokens)
    }

    @Test
    fun `createKoogLLMService should create service from config`() {
        val config = LLMProviderConfig(
            providerType = LLMProviderType.OPENAI,
            apiKey = "test-key",
            modelId = "gpt-4o-mini",
            temperature = 0.5,
            maxTokens = 2048
        )

        val service = createKoogLLMService(config)

        assertNotNull(service)
        assertEquals("OPENAI / gpt-4o-mini", service.getProviderInfo())
    }
}