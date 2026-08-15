package com.maxvibes.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

data class VoiceTranscriptionConfiguration(
    val endpoint: String = VoiceTranscriptionSettings.DEFAULT_ENDPOINT,
    val model: String = VoiceTranscriptionSettings.DEFAULT_MODEL,
    val language: String = "",
    val glossary: String = "",
    val apiKey: String = ""
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && endpoint.isNotBlank() && model.isNotBlank()

    fun glossaryTerms(): List<String> = glossary
        .split(',', '\n', '\r')
        .map(String::trim)
        .filter(String::isNotEmpty)
}

/**
 * Application-level voice settings. The API key lives in PasswordSafe; only endpoint,
 * model, language and glossary are serialized to maxvibes-voice.xml.
 */
@Service(Service.Level.APP)
@State(
    name = "MaxVibesVoiceTranscriptionSettings",
    storages = [Storage("maxvibes-voice.xml")]
)
class VoiceTranscriptionSettings : PersistentStateComponent<VoiceTranscriptionSettings.State> {
    data class State(
        var endpoint: String = DEFAULT_ENDPOINT,
        var model: String = DEFAULT_MODEL,
        var language: String = "",
        var glossary: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var endpoint: String
        get() = myState.endpoint
        set(value) {
            myState.endpoint = value
        }

    var model: String
        get() = myState.model
        set(value) {
            myState.model = value
        }

    var language: String
        get() = myState.language
        set(value) {
            myState.language = value
        }

    var glossary: String
        get() = myState.glossary
        set(value) {
            myState.glossary = value
        }

    var apiKey: String
        get() = PasswordSafe.instance.getPassword(credentialAttributes()).orEmpty()
        set(value) {
            val credentials = value.takeIf(String::isNotBlank)?.let { Credentials("", it) }
            PasswordSafe.instance.set(credentialAttributes(), credentials)
        }

    fun configuration(): VoiceTranscriptionConfiguration = VoiceTranscriptionConfiguration(
        endpoint = endpoint.trim(),
        model = model.trim(),
        language = language.trim(),
        glossary = glossary,
        apiKey = apiKey.trim()
    )

    val isConfigured: Boolean
        get() = configuration().isConfigured

    private fun credentialAttributes(): CredentialAttributes = CredentialAttributes(
        generateServiceName("MaxVibes", VOICE_API_KEY_NAME)
    )

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val DEFAULT_MODEL = "gpt-4o-mini-transcribe"
        private const val VOICE_API_KEY_NAME = "voice_transcription_api_key"

        fun getInstance(): VoiceTranscriptionSettings =
            ApplicationManager.getApplication().getService(VoiceTranscriptionSettings::class.java)
    }
}
