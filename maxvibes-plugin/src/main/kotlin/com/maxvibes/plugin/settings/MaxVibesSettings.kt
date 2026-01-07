// maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettings.kt
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
 * Stores LLM provider configuration.
 *
 * API keys are stored securely using IntelliJ's PasswordSafe.
 */
@State(
    name = "MaxVibesSettings",
    storages = [Storage("maxvibes.xml")]
)
class MaxVibesSettings : PersistentStateComponent<MaxVibesSettings.State> {

    data class State(
        var provider: String = "ANTHROPIC",
        var modelId: String = "claude-sonnet-4-20250514",
        var ollamaBaseUrl: String = "http://localhost:11434",
        var temperature: Double = 0.2,
        var maxTokens: Int = 4096,
        var enableMockFallback: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    // Provider
    var provider: String
        get() = myState.provider
        set(value) { myState.provider = value }

    // Model ID
    var modelId: String
        get() = myState.modelId
        set(value) { myState.modelId = value }

    // Ollama Base URL
    var ollamaBaseUrl: String
        get() = myState.ollamaBaseUrl
        set(value) { myState.ollamaBaseUrl = value }

    // Temperature
    var temperature: Double
        get() = myState.temperature
        set(value) { myState.temperature = value }

    // Max Tokens
    var maxTokens: Int
        get() = myState.maxTokens
        set(value) { myState.maxTokens = value }

    // Enable Mock Fallback when no API key configured
    var enableMockFallback: Boolean
        get() = myState.enableMockFallback
        set(value) { myState.enableMockFallback = value }

    // ========== Secure API Key Storage ==========

    /**
     * Get OpenAI API Key from secure storage
     */
    var openAIApiKey: String
        get() = getSecureKey(OPENAI_KEY_NAME)
        set(value) = setSecureKey(OPENAI_KEY_NAME, value)

    /**
     * Get Anthropic API Key from secure storage
     */
    var anthropicApiKey: String
        get() = getSecureKey(ANTHROPIC_KEY_NAME)
        set(value) = setSecureKey(ANTHROPIC_KEY_NAME, value)

    /**
     * Get the API key for the currently selected provider
     */
    val currentApiKey: String
        get() = when (provider) {
            "OPENAI" -> openAIApiKey
            "ANTHROPIC" -> anthropicApiKey
            "OLLAMA" -> "" // Ollama doesn't need API key
            else -> ""
        }

    /**
     * Check if the current provider is properly configured
     */
    val isConfigured: Boolean
        get() = when (provider) {
            "OPENAI" -> openAIApiKey.isNotBlank()
            "ANTHROPIC" -> anthropicApiKey.isNotBlank()
            "OLLAMA" -> ollamaBaseUrl.isNotBlank()
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
                "claude-sonnet-4-20250514" to "Claude Sonnet 4 (Recommended)",
                "claude-opus-4-1" to "Claude Opus 4.1 (Most Capable)",
                "claude-haiku-3-5" to "Claude Haiku 3.5 (Fastest)"
            ),
            "OLLAMA" to listOf(
                "llama3.2" to "Llama 3.2 (Default)",
                "codellama" to "CodeLlama",
                "mistral" to "Mistral",
                "qwen2.5-coder" to "Qwen 2.5 Coder"
            )
        )

        // Provider display names
        val PROVIDERS = listOf(
            "ANTHROPIC" to "Anthropic Claude",
            "OPENAI" to "OpenAI GPT",
            "OLLAMA" to "Ollama (Local)"
        )
    }
}