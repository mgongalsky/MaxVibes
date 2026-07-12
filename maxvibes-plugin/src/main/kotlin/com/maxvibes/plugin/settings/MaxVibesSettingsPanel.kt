// maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettingsPanel.kt
package com.maxvibes.plugin.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maxvibes.adapter.llm.LLMServiceFactory
import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
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

    // Claude Code section
    private val claudeCodePathField: TextFieldWithBrowseButton
    private val claudeCodeExtraArgsField: JBTextField
    private val claudeCodeModelField: JBTextField = JBTextField().apply {
        columns = 30
        emptyText.text = "Auto, sonnet, opus, haiku, or full model name"
        toolTipText = "Blank uses the Claude Code CLI default model."
    }
    private val claudeCodeMaxOutputTokensField: JBTextField = JBTextField().apply {
        columns = 8
        toolTipText = "Per-response cap via CLAUDE_CODE_MAX_OUTPUT_TOKENS. 0 uses the CLI default."
    }
    private val claudeCodeThinkingBudgetField: JBTextField = JBTextField().apply {
        columns = 8
        toolTipText = "Max reasoning tokens per turn via MAX_THINKING_TOKENS. 0 uses the CLI default."
    }
    private val claudeCodeEffortCombo: ComboBox<String> =
        ComboBox(arrayOf("Auto", "low", "medium", "high", "xhigh", "max")).apply {
            toolTipText =
                "Reasoning effort via CLAUDE_CODE_EFFORT_LEVEL. Auto = model default. Unsupported levels fall back; pre-4.6 models ignore it."
        }
    private val claudeCodeReadTimeoutField: JBTextField
    private val claudeCodeStartTimeoutField: JBTextField

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

        // Claude Code fields
        claudeCodePathField = TextFieldWithBrowseButton().apply {
            textField.columns = 30
            (textField as? JBTextField)?.emptyText?.text = "claude (in PATH) or absolute path"
            @Suppress("DEPRECATION")
            addBrowseFolderListener(
                "Claude Code Binary",
                "Path to the claude CLI binary",
                null,
                FileChooserDescriptorFactory.createSingleFileDescriptor()
            )
        }
        claudeCodeExtraArgsField = JBTextField().apply {
            columns = 30
            emptyText.text = "e.g. --allowedTools \"\""
        }
        claudeCodeReadTimeoutField = JBTextField().apply { columns = 6 }
        claudeCodeStartTimeoutField = JBTextField().apply { columns = 6 }

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
            .addComponent(JBLabel("Claude Code (CLI mode):"))
            .addLabeledComponent(JBLabel("Binary path:"), claudeCodePathField)
            .addLabeledComponent(JBLabel("Extra CLI args:"), claudeCodeExtraArgsField)
            .addLabeledComponent(JBLabel("Model:"), claudeCodeModelField)
            .addLabeledComponent(JBLabel("Max output tokens:"), claudeCodeMaxOutputTokensField)
            .addLabeledComponent(JBLabel("Thinking budget:"), claudeCodeThinkingBudgetField)
            .addLabeledComponent(JBLabel("Effort:"), claudeCodeEffortCombo)
            .addLabeledComponent(JBLabel("Read timeout (sec):"), claudeCodeReadTimeoutField)
            .addLabeledComponent(JBLabel("Start timeout (sec):"), claudeCodeStartTimeoutField)
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
                    statusLabel.text = "\u2713 Connected: $info"
                    statusLabel.foreground = java.awt.Color(0, 128, 0)
                    testConnectionButton.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "\u2717 Error: ${e.message?.take(50)}"
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
        val providerIndex = MaxVibesSettings.PROVIDERS.indexOfFirst { it.first == settings.provider }
        if (providerIndex >= 0) {
            providerComboBox.selectedIndex = providerIndex
        }
        updateProviderPanels()
        updateModelComboBox()

        openAIKeyField.text = settings.openAIApiKey
        anthropicKeyField.text = settings.anthropicApiKey

        val models = MaxVibesSettings.DEFAULT_MODELS[settings.provider] ?: emptyList()
        val modelIndex = models.indexOfFirst { it.first == settings.modelId }
        if (modelIndex >= 0) {
            modelComboBox.selectedIndex = modelIndex
            customModelField.text = ""
        } else {
            customModelField.text = settings.modelId
        }

        ollamaUrlField.text = settings.ollamaBaseUrl
        temperatureSlider.value = (settings.temperature * 100).toInt()
        temperatureLabel.text = String.format("%.2f", settings.temperature)
        mockFallbackCheckBox.isSelected = settings.enableMockFallback

        claudeCodePathField.text = settings.claudeCodePath
        claudeCodeExtraArgsField.text = settings.claudeCodeExtraArgs
        claudeCodeModelField.text = settings.claudeCodeModel
        claudeCodeMaxOutputTokensField.text = settings.claudeCodeMaxOutputTokens.toString()
        claudeCodeThinkingBudgetField.text = settings.claudeCodeThinkingBudget.toString()
        claudeCodeEffortCombo.selectedItem = settings.claudeCodeEffortLevel.ifBlank { "Auto" }
        claudeCodeReadTimeoutField.text = settings.claudeCodeReadTimeoutSec.toString()
        claudeCodeStartTimeoutField.text = settings.claudeCodeStartTimeoutSec.toString()

        statusLabel.text = " "
    }

    fun saveSettings(settings: MaxVibesSettings) {
        settings.provider = getSelectedProviderKey()
        settings.modelId = getSelectedModelId()
        settings.ollamaBaseUrl = ollamaUrlField.text
        settings.temperature = temperatureSlider.value / 100.0
        settings.enableMockFallback = mockFallbackCheckBox.isSelected

        settings.openAIApiKey = String(openAIKeyField.password)
        settings.anthropicApiKey = String(anthropicKeyField.password)

        settings.claudeCodePath = claudeCodePathField.text.trim().ifBlank { "claude" }
        settings.claudeCodeExtraArgs = claudeCodeExtraArgsField.text
        settings.claudeCodeModel = claudeCodeModelField.text.trim()
        settings.claudeCodeMaxOutputTokens =
            claudeCodeMaxOutputTokensField.text.trim().toIntOrNull()?.coerceIn(0, 200_000)
                ?: settings.claudeCodeMaxOutputTokens
        settings.claudeCodeThinkingBudget =
            claudeCodeThinkingBudgetField.text.trim().toIntOrNull()?.coerceIn(0, 200_000)
                ?: settings.claudeCodeThinkingBudget
        settings.claudeCodeEffortLevel =
            (claudeCodeEffortCombo.selectedItem as? String)?.takeUnless { it == "Auto" } ?: ""
        settings.claudeCodeReadTimeoutSec =
            claudeCodeReadTimeoutField.text.trim().toIntOrNull()?.coerceAtLeast(1)
                ?: settings.claudeCodeReadTimeoutSec
        settings.claudeCodeStartTimeoutSec =
            claudeCodeStartTimeoutField.text.trim().toIntOrNull()?.coerceAtLeast(1)
                ?: settings.claudeCodeStartTimeoutSec
    }

    fun isModified(settings: MaxVibesSettings): Boolean {
        val pathChanged = settings.claudeCodePath != claudeCodePathField.text.trim().ifBlank { "claude" }
        val argsChanged = settings.claudeCodeExtraArgs != claudeCodeExtraArgsField.text
        val claudeModelChanged = settings.claudeCodeModel != claudeCodeModelField.text.trim()
        val maxOutputChanged =
            settings.claudeCodeMaxOutputTokens.toString() != claudeCodeMaxOutputTokensField.text.trim()
        val thinkingBudgetChanged =
            settings.claudeCodeThinkingBudget.toString() != claudeCodeThinkingBudgetField.text.trim()
        val effortChanged =
            settings.claudeCodeEffortLevel.ifBlank { "Auto" } !=
                    (claudeCodeEffortCombo.selectedItem as? String ?: "Auto")
        val readTimeoutChanged =
            settings.claudeCodeReadTimeoutSec.toString() != claudeCodeReadTimeoutField.text.trim()
        val startTimeoutChanged =
            settings.claudeCodeStartTimeoutSec.toString() != claudeCodeStartTimeoutField.text.trim()

        return settings.provider != getSelectedProviderKey() ||
                settings.modelId != getSelectedModelId() ||
                settings.ollamaBaseUrl != ollamaUrlField.text ||
                settings.temperature != temperatureSlider.value / 100.0 ||
                settings.enableMockFallback != mockFallbackCheckBox.isSelected ||
                settings.openAIApiKey != String(openAIKeyField.password) ||
                settings.anthropicApiKey != String(anthropicKeyField.password) ||
                pathChanged ||
                argsChanged ||
                claudeModelChanged ||
                maxOutputChanged ||
                thinkingBudgetChanged ||
                effortChanged ||
                readTimeoutChanged ||
                startTimeoutChanged
    }
}
