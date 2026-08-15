package com.maxvibes.plugin.settings

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JPanel

/** Editor for the independent OpenAI-compatible voice transcription configuration. */
class VoiceTranscriptionSettingsPanel {
    private val apiKeyField = JBPasswordField().apply {
        columns = 40
        emptyText.text = "API key for the transcription provider"
    }
    private val endpointField = JBTextField().apply {
        columns = 40
        emptyText.text = VoiceTranscriptionSettings.DEFAULT_ENDPOINT
    }
    private val modelField = JBTextField().apply {
        columns = 30
        emptyText.text = VoiceTranscriptionSettings.DEFAULT_MODEL
    }
    private val languageField = JBTextField().apply {
        columns = 8
        emptyText.text = "Auto (for example: ru or en)"
        toolTipText = "Optional ISO-639-1 language hint. Blank enables automatic detection."
    }
    private val glossaryField = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "Comma- or line-separated project terms passed as a transcription hint."
    }

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addComponent(JBLabel("Voice transcription:"))
        .addLabeledComponent(JBLabel("API key:"), apiKeyField)
        .addLabeledComponent(JBLabel("Endpoint:"), endpointField)
        .addLabeledComponent(JBLabel("Model:"), modelField)
        .addLabeledComponent(JBLabel("Language:"), languageField)
        .addLabeledComponent(
            JBLabel("Glossary:"),
            JBScrollPane(glossaryField).apply {
                border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
            }
        )
        .panel

    fun loadSettings(settings: VoiceTranscriptionSettings) {
        apiKeyField.text = settings.apiKey
        endpointField.text = settings.endpoint
        modelField.text = settings.model
        languageField.text = settings.language
        glossaryField.text = settings.glossary
    }

    fun saveSettings(settings: VoiceTranscriptionSettings) {
        settings.apiKey = String(apiKeyField.password).trim()
        settings.endpoint = endpointField.text.trim()
        settings.model = modelField.text.trim()
        settings.language = languageField.text.trim()
        settings.glossary = glossaryField.text
    }

    fun isModified(settings: VoiceTranscriptionSettings): Boolean =
        settings.apiKey != String(apiKeyField.password).trim() ||
                settings.endpoint != endpointField.text.trim() ||
                settings.model != modelField.text.trim() ||
                settings.language != languageField.text.trim() ||
                settings.glossary != glossaryField.text
}
