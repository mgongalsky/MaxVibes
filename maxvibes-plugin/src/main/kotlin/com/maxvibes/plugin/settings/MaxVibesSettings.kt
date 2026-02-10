package com.maxvibes.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persistent settings for MaxVibes plugin.
 * Stores LLM provider configuration, interaction mode, and cheap LLM config.
 *
 * API keys are stored securely using IntelliJ's PasswordSafe.
 */
@State(
    name = "MaxVibesSettings",
    storages = [Storage("maxvibes.xml")]
)
class MaxVibesSettings : PersistentStateComponent<MaxVibesSettings.State> {

    data class State(
        // ===== Primary LLM (API mode) =====
        var provider: String = "ANTHROPIC",
        var modelId: String = "claude-sonnet-4-5-20250929",
        var ollamaBaseUrl: String = "http://localhost:11434",
        var temperature: Double = 0.2,
        var maxTokens: Int = 32768,
        var enableMockFallback: Boolean = true,

        // ===== Interaction Mode =====
        var interactionMode: String = "API",  // API, CLIPBOARD, CHEAP_API

        // ===== Cheap LLM (for CHEAP_API mode) =====
        var cheapProvider: String = "ANTHROPIC",
        var cheapModelId: String = "claude-haiku-4-5-20251001",
        var cheapOllamaBaseUrl: String = "http://localhost:11434",
        var cheapTemperature: Double = 0.1,
        var cheapMaxTokens: Int = 16384
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    // ===== Primary LLM =====

    var provider: String
        get() = myState.provider
        set(value) { myState.provider = value }

    var modelId: String
        get() = myState.modelId
        set(value) { myState.modelId = value }

    var ollamaBaseUrl: String
        get() = myState.ollamaBaseUrl
        set(value) { myState.ollamaBaseUrl = value }

    var temperature: Double
        get() = myState.temperature
        set(value) { myState.temperature = value }

    var maxTokens: Int
        get() = myState.maxTokens
        set(value) { myState.maxTokens = value }

    var enableMockFallback: Boolean
        get() = myState.enableMockFallback
        set(value) { myState.enableMockFallback = value }

    // ===== Interaction Mode =====

    var interactionMode: String
        get() = myState.interactionMode
        set(value) { myState.interactionMode = value }

    // ===== Cheap LLM =====

    var cheapProvider: String
        get() = myState.cheapProvider
        set(value) { myState.cheapProvider = value }

    var cheapModelId: String
        get() = myState.cheapModelId
        set(value) { myState.cheapModelId = value }

    var cheapOllamaBaseUrl: String
        get() = myState.cheapOllamaBaseUrl
        set(value) { myState.cheapOllamaBaseUrl = value }

    var cheapTemperature: Double
        get() = myState.cheapTemperature
        set(value) { myState.cheapTemperature = value }

    var cheapMaxTokens: Int
        get() = myState.cheapMaxTokens
        set(value) { myState.cheapMaxTokens = value }

    // ========== Secure API Key Storage ==========

    var openAIApiKey: String
        get() = getSecureKey(OPENAI_KEY_NAME)
        set(value) = setSecureKey(OPENAI_KEY_NAME, value)

    var anthropicApiKey: String
        get() = getSecureKey(ANTHROPIC_KEY_NAME)
        set(value) = setSecureKey(ANTHROPIC_KEY_NAME, value)

    /** API key for cheap/external provider (DeepSeek, etc.) */
    var cheapApiKey: String
        get() = getSecureKey(CHEAP_API_KEY_NAME)
        set(value) = setSecureKey(CHEAP_API_KEY_NAME, value)

    val currentApiKey: String
        get() = when (provider) {
            "OPENAI" -> openAIApiKey
            "ANTHROPIC" -> anthropicApiKey
            "OLLAMA" -> ""
            else -> ""
        }

    /** API key for cheap provider — reuses main keys for same providers */
    val currentCheapApiKey: String
        get() = when (cheapProvider) {
            "OPENAI" -> openAIApiKey
            "ANTHROPIC" -> anthropicApiKey
            "DEEPSEEK" -> cheapApiKey
            "OLLAMA" -> ""
            else -> cheapApiKey
        }

    val isConfigured: Boolean
        get() = when (provider) {
            "OPENAI" -> openAIApiKey.isNotBlank()
            "ANTHROPIC" -> anthropicApiKey.isNotBlank()
            "OLLAMA" -> ollamaBaseUrl.isNotBlank()
            else -> false
        }

    val isCheapConfigured: Boolean
        get() = when (cheapProvider) {
            "OPENAI" -> openAIApiKey.isNotBlank()
            "ANTHROPIC" -> anthropicApiKey.isNotBlank()
            "DEEPSEEK" -> cheapApiKey.isNotBlank()
            "OLLAMA" -> cheapOllamaBaseUrl.isNotBlank()
            else -> false
        }

    private fun getSecureKey(keyName: String): String {
        val attributes = createCredentialAttributes(keyName)
        return PasswordSafe.instance.getPassword(attributes) ?: ""
    }

    private fun setSecureKey(keyName: String, value: String) {
        val attributes = createCredentialAttributes(keyName)
        if (value.isBlank()) {
            PasswordSafe.instance.set(attributes, null)
        } else {
            PasswordSafe.instance.set(attributes, Credentials("", value))
        }
    }

    private fun createCredentialAttributes(key: String): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("MaxVibes", key)
        )
    }

    companion object {
        private const val OPENAI_KEY_NAME = "openai_api_key"
        private const val ANTHROPIC_KEY_NAME = "anthropic_api_key"
        private const val CHEAP_API_KEY_NAME = "cheap_llm_api_key"

        fun getInstance(): MaxVibesSettings {
            return ApplicationManager.getApplication().getService(MaxVibesSettings::class.java)
        }

        // Default models for each provider
        val DEFAULT_MODELS = mapOf(
            "OPENAI" to listOf(
                "gpt-5.2" to "GPT-5.2 (Recommended)",
                "gpt-5.2-pro" to "GPT-5.2 Pro (Most Capable)",
                "gpt-4o" to "GPT-4o",
                "gpt-4o-mini" to "GPT-4o Mini (Faster)",
                "o3-mini" to "O3 Mini (Reasoning)"
            ),
            "ANTHROPIC" to listOf(
                "claude-sonnet-4-5-20250929" to "Claude Sonnet 4.5 (Recommended)",
                "claude-opus-4-6" to "Claude Opus 4.6 (Most Intelligent)",
                "claude-opus-4-5-20251101" to "Claude Opus 4.5",
                "claude-haiku-4-5-20251001" to "Claude Haiku 4.5 (Fastest)",
                "claude-sonnet-4-20250514" to "Claude Sonnet 4 (Legacy)"
            ),
            "OLLAMA" to listOf(
                "llama3.2" to "Llama 3.2 (Default)",
                "codellama" to "CodeLlama",
                "mistral" to "Mistral",
                "qwen2.5-coder" to "Qwen 2.5 Coder"
            )
        )

        /** Cheap models — optimized for cost */
        val CHEAP_MODELS = mapOf(
            "ANTHROPIC" to listOf(
                "claude-haiku-4-5-20251001" to "Claude Haiku 4.5 (\$0.80/\$4 per M tokens)",
                "claude-sonnet-4-5-20250929" to "Claude Sonnet 4.5 (\$3/\$15 per M tokens)"
            ),
            "OPENAI" to listOf(
                "gpt-4o-mini" to "GPT-4o Mini (\$0.15/\$0.60 per M tokens)",
                "gpt-4o" to "GPT-4o (\$2.50/\$10 per M tokens)"
            ),
            "DEEPSEEK" to listOf(
                "deepseek-chat" to "DeepSeek V3 (\$0.14/\$0.28 per M tokens)",
                "deepseek-coder" to "DeepSeek Coder (\$0.14/\$0.28 per M tokens)"
            ),
            "OLLAMA" to listOf(
                "qwen2.5-coder:7b" to "Qwen 2.5 Coder 7B (Free, Local)",
                "codellama:7b" to "CodeLlama 7B (Free, Local)",
                "deepseek-coder:6.7b" to "DeepSeek Coder 6.7B (Free, Local)"
            )
        )

        val PROVIDERS = listOf(
            "ANTHROPIC" to "Anthropic Claude",
            "OPENAI" to "OpenAI GPT",
            "OLLAMA" to "Ollama (Local)"
        )

        val CHEAP_PROVIDERS = listOf(
            "ANTHROPIC" to "Anthropic (Haiku)",
            "OPENAI" to "OpenAI (Mini)",
            "DEEPSEEK" to "DeepSeek (Cheapest)",
            "OLLAMA" to "Ollama (Free, Local)"
        )

        /** Interaction mode display names */
        val INTERACTION_MODES = listOf(
            "API" to "\uD83D\uDD0C API (Direct, pay-per-token)",
            "CLIPBOARD" to "\uD83D\uDCCB Clipboard (Copy-paste, subscription)",
            "CHEAP_API" to "\uD83D\uDCB0 Cheap API (Budget model)"
        )
    }
}