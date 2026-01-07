// maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettingsPanel.kt
package com.maxvibes.plugin.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maxvibes.adapter.llm.LLMServiceFactory
import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings panel for MaxVibes LLM configuration.
 * Provides UI for selecting provider, entering API keys, and configuring model.
 */
class MaxVibesSettingsPanel {

    private val providerComboBox: ComboBox<String>
    private val openAIKeyField: JBPasswordField
    private val anthropicKeyField: JBPasswordField
    private val modelComboBox: ComboBox<String>
    private val customModelField: JBTextField
    private val ollamaUrlField: JBTextField
    private val temperatureSlider: JSlider
    private val temperatureLabel: JBLabel
    private val mockFallbackCheckBox: JCheckBox
    private val testConnectionButton: JButton
    private val statusLabel: JBLabel

    // Panels for conditional display
    private val openAIPanel: JPanel
    private val anthropicPanel: JPanel
    private val ollamaPanel: JPanel

    val panel: JPanel

    init {
        // Initialize components
        providerComboBox = ComboBox(MaxVibesSettings.PROVIDERS.map { it.second }.toTypedArray())

        openAIKeyField = JBPasswordField().apply {
            columns = 40
            emptyText.text = "sk-..."
        }

        anthropicKeyField = JBPasswordField().apply {
            columns = 40
            emptyText.text = "sk-ant-..."
        }

        modelComboBox = ComboBox<String>().apply {
            isEditable = false
        }

        customModelField = JBTextField().apply {
            columns = 30
            emptyText.text = "Or enter custom model ID"
        }

        ollamaUrlField = JBTextField().apply {
            columns = 30
            text = "http://localhost:11434"
        }

        temperatureSlider = JSlider(0, 100, 20).apply {
            majorTickSpacing = 25
            minorTickSpacing = 5
            paintTicks = true
            paintLabels = true
            val labels = java.util.Hashtable<Int, JLabel>()
            labels[0] = JLabel("0.0")
            labels[50] = JLabel("0.5")
            labels[100] = JLabel("1.0")
            labelTable = labels
        }

        temperatureLabel = JBLabel("0.2")

        mockFallbackCheckBox = JCheckBox("Enable mock fallback when API is not configured").apply {
            isSelected = true
        }

        testConnectionButton = JButton("Test Connection")
        statusLabel = JBLabel(" ")

        // Create provider-specific panels
        openAIPanel = createOpenAIPanel()
        anthropicPanel = createAnthropicPanel()
        ollamaPanel = createOllamaPanel()

        // Build the main panel
        panel = buildPanel()

        // Setup listeners
        setupListeners()

        // Initial state
        updateProviderPanels()
        updateModelComboBox()
    }

    private fun createOpenAIPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(5)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JBLabel("OpenAI API Key: "))
                add(openAIKeyField)
                add(Box.createHorizontalStrut(10))
                add(createHelpLink("https://platform.openai.com/api-keys"))
            }, BorderLayout.CENTER)
        }
    }

    private fun createAnthropicPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(5)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JBLabel("Anthropic API Key: "))
                add(anthropicKeyField)
                add(Box.createHorizontalStrut(10))
                add(createHelpLink("https://console.anthropic.com/"))
            }, BorderLayout.CENTER)
        }
    }

    private fun createOllamaPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(5)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(JBLabel("Ollama URL: "))
                add(ollamaUrlField)
                add(Box.createHorizontalStrut(10))
                add(createHelpLink("https://ollama.ai/"))
            }, BorderLayout.CENTER)
        }
    }

    private fun createHelpLink(url: String): JButton {
        return JButton("?").apply {
            toolTipText = "Open $url"
            isFocusPainted = false
            addActionListener {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun buildPanel(): JPanel {
        val temperaturePanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(temperatureSlider)
            add(temperatureLabel)
        }

        val modelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(modelComboBox)
            add(JBLabel(" or "))
            add(customModelField)
        }

        val testPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(testConnectionButton)
            add(statusLabel)
        }

        // Provider panels container
        val providerPanelsContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(openAIPanel)
            add(anthropicPanel)
            add(ollamaPanel)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("LLM Provider:"), providerComboBox)
            .addComponent(providerPanelsContainer)
            .addSeparator()
            .addLabeledComponent(JBLabel("Model:"), modelPanel)
            .addLabeledComponent(JBLabel("Temperature:"), temperaturePanel)
            .addSeparator()
            .addComponent(mockFallbackCheckBox)
            .addComponent(testPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply {
                border = JBUI.Borders.empty(10)
            }
    }

    private fun setupListeners() {
        // Provider change listener
        providerComboBox.addActionListener {
            updateProviderPanels()
            updateModelComboBox()
        }

        // Temperature slider listener
        temperatureSlider.addChangeListener {
            val temp = temperatureSlider.value / 100.0
            temperatureLabel.text = String.format("%.2f", temp)
        }

        // Custom model field listener
        customModelField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onCustomModelChanged()
            override fun removeUpdate(e: DocumentEvent?) = onCustomModelChanged()
            override fun changedUpdate(e: DocumentEvent?) = onCustomModelChanged()
        })

        // Test connection button
        testConnectionButton.addActionListener {
            testConnection()
        }
    }

    private fun onCustomModelChanged() {
        if (customModelField.text.isNotBlank()) {
            modelComboBox.isEnabled = false
        } else {
            modelComboBox.isEnabled = true
        }
    }

    private fun updateProviderPanels() {
        val providerKey = getSelectedProviderKey()
        openAIPanel.isVisible = providerKey == "OPENAI"
        anthropicPanel.isVisible = providerKey == "ANTHROPIC"
        ollamaPanel.isVisible = providerKey == "OLLAMA"
    }

    private fun updateModelComboBox() {
        val providerKey = getSelectedProviderKey()
        val models = MaxVibesSettings.DEFAULT_MODELS[providerKey] ?: emptyList()

        modelComboBox.removeAllItems()
        models.forEach { (_, displayName) ->
            modelComboBox.addItem(displayName)
        }

        if (modelComboBox.itemCount > 0) {
            modelComboBox.selectedIndex = 0
        }
    }

    private fun getSelectedProviderKey(): String {
        val index = providerComboBox.selectedIndex
        return if (index >= 0) {
            MaxVibesSettings.PROVIDERS[index].first
        } else {
            "ANTHROPIC"
        }
    }

    private fun getSelectedModelId(): String {
        // If custom model is specified, use it
        if (customModelField.text.isNotBlank()) {
            return customModelField.text.trim()
        }

        // Otherwise use the selected preset
        val providerKey = getSelectedProviderKey()
        val models = MaxVibesSettings.DEFAULT_MODELS[providerKey] ?: emptyList()
        val index = modelComboBox.selectedIndex

        return if (index >= 0 && index < models.size) {
            models[index].first
        } else {
            models.firstOrNull()?.first ?: ""
        }
    }

    private fun testConnection() {
        statusLabel.text = "Testing..."
        testConnectionButton.isEnabled = false

        Thread {
            try {
                val config = createCurrentConfig()
                val service = LLMServiceFactory.create(config)

                // Simple test - just check if we can get provider info
                val info = service.getProviderInfo()

                SwingUtilities.invokeLater {
                    statusLabel.text = "✓ Connected: $info"
                    statusLabel.foreground = java.awt.Color(0, 128, 0)
                    testConnectionButton.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "✗ Error: ${e.message?.take(50)}"
                    statusLabel.foreground = java.awt.Color(200, 0, 0)
                    testConnectionButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun createCurrentConfig(): LLMProviderConfig {
        val providerKey = getSelectedProviderKey()
        val modelId = getSelectedModelId()
        val temp = temperatureSlider.value / 100.0

        return when (providerKey) {
            "OPENAI" -> LLMProviderConfig(
                providerType = LLMProviderType.OPENAI,
                apiKey = String(openAIKeyField.password),
                modelId = modelId,
                temperature = temp
            )
            "ANTHROPIC" -> LLMProviderConfig(
                providerType = LLMProviderType.ANTHROPIC,
                apiKey = String(anthropicKeyField.password),
                modelId = modelId,
                temperature = temp
            )
            "OLLAMA" -> LLMProviderConfig(
                providerType = LLMProviderType.OLLAMA,
                apiKey = "",
                modelId = modelId,
                baseUrl = ollamaUrlField.text,
                temperature = temp
            )
            else -> throw IllegalStateException("Unknown provider: $providerKey")
        }
    }

    // ========== Settings Load/Save ==========

    fun loadSettings(settings: MaxVibesSettings) {
        // Provider
        val providerIndex = MaxVibesSettings.PROVIDERS.indexOfFirst { it.first == settings.provider }
        if (providerIndex >= 0) {
            providerComboBox.selectedIndex = providerIndex
        }
        updateProviderPanels()
        updateModelComboBox()

        // API Keys
        openAIKeyField.text = settings.openAIApiKey
        anthropicKeyField.text = settings.anthropicApiKey

        // Model
        val models = MaxVibesSettings.DEFAULT_MODELS[settings.provider] ?: emptyList()
        val modelIndex = models.indexOfFirst { it.first == settings.modelId }
        if (modelIndex >= 0) {
            modelComboBox.selectedIndex = modelIndex
            customModelField.text = ""
        } else {
            // Custom model
            customModelField.text = settings.modelId
        }

        // Ollama URL
        ollamaUrlField.text = settings.ollamaBaseUrl

        // Temperature
        temperatureSlider.value = (settings.temperature * 100).toInt()
        temperatureLabel.text = String.format("%.2f", settings.temperature)

        // Mock fallback
        mockFallbackCheckBox.isSelected = settings.enableMockFallback

        // Reset status
        statusLabel.text = " "
    }

    fun saveSettings(settings: MaxVibesSettings) {
        settings.provider = getSelectedProviderKey()
        settings.modelId = getSelectedModelId()
        settings.ollamaBaseUrl = ollamaUrlField.text
        settings.temperature = temperatureSlider.value / 100.0
        settings.enableMockFallback = mockFallbackCheckBox.isSelected

        // Save API keys securely
        settings.openAIApiKey = String(openAIKeyField.password)
        settings.anthropicApiKey = String(anthropicKeyField.password)
    }

    fun isModified(settings: MaxVibesSettings): Boolean {
        return settings.provider != getSelectedProviderKey() ||
                settings.modelId != getSelectedModelId() ||
                settings.ollamaBaseUrl != ollamaUrlField.text ||
                settings.temperature != temperatureSlider.value / 100.0 ||
                settings.enableMockFallback != mockFallbackCheckBox.isSelected ||
                settings.openAIApiKey != String(openAIKeyField.password) ||
                settings.anthropicApiKey != String(anthropicKeyField.password)
    }
}