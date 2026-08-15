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

/** Settings panel for MaxVibes LLM and coding-agent configuration. */
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

    // Coding Agent provider
    private val codingAgentProviderCombo: ComboBox<String> =
        ComboBox(MaxVibesSettings.CODING_AGENT_PROVIDERS.map { it.second }.toTypedArray()).apply {
            toolTipText = "CLI provider used by Coding Agent mode."
        }

    // Claude Code
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
            toolTipText = "Reasoning effort via CLAUDE_CODE_EFFORT_LEVEL. Auto = model default."
        }
    private val claudeCodeReadTimeoutField: JBTextField
    private val claudeCodeStartTimeoutField: JBTextField

    // Codex
    private val codexPathField: TextFieldWithBrowseButton = TextFieldWithBrowseButton().apply {
        textField.columns = 30
        (textField as? JBTextField)?.emptyText?.text = "codex (in PATH) or absolute path"
        @Suppress("DEPRECATION")
        addBrowseFolderListener(
            "Codex Binary",
            "Path to the Codex CLI binary",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )
    }
    private val codexExtraArgsField: JBTextField = JBTextField().apply {
        columns = 30
        emptyText.text = "Additional app-server arguments"
    }
    private val codexModelField: JBTextField = JBTextField().apply {
        columns = 30
        emptyText.text = "Blank = Codex default model"
    }
    private val codexReasoningEffortField: JBTextField = JBTextField().apply {
        columns = 12
        emptyText.text = "Auto"
        toolTipText = "Blank uses the Codex default reasoning effort."
    }
    private val codexReadTimeoutField: JBTextField = JBTextField().apply { columns = 6 }
    private val codexStartTimeoutField: JBTextField = JBTextField().apply { columns = 6 }

    private val openAIPanel: JPanel
    private val anthropicPanel: JPanel
    private val ollamaPanel: JPanel

    private lateinit var claudeCodeAgentPanel: JPanel
    private lateinit var codexAgentPanel: JPanel

    val panel: JPanel

    init {
        providerComboBox = ComboBox(MaxVibesSettings.PROVIDERS.map { it.second }.toTypedArray())

        openAIKeyField = JBPasswordField().apply {
            columns = 40
            emptyText.text = "sk-..."
        }
        anthropicKeyField = JBPasswordField().apply {
            columns = 40
            emptyText.text = "sk-ant-..."
        }
        modelComboBox = ComboBox<String>().apply { isEditable = false }
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
            emptyText.text = "Additional Claude CLI arguments"
        }
        claudeCodeReadTimeoutField = JBTextField().apply { columns = 6 }
        claudeCodeStartTimeoutField = JBTextField().apply { columns = 6 }

        openAIPanel = createOpenAIPanel()
        anthropicPanel = createAnthropicPanel()
        ollamaPanel = createOllamaPanel()

        panel = buildPanel()
        setupListeners()
        updateProviderPanels()
        updateCodingAgentPanels()
        updateModelComboBox()
    }

    private fun createOpenAIPanel(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(5)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JBLabel("OpenAI API Key: "))
            add(openAIKeyField)
            add(Box.createHorizontalStrut(10))
            add(createHelpLink("https://platform.openai.com/api-keys"))
        }, BorderLayout.CENTER)
    }

    private fun createAnthropicPanel(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(5)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JBLabel("Anthropic API Key: "))
            add(anthropicKeyField)
            add(Box.createHorizontalStrut(10))
            add(createHelpLink("https://console.anthropic.com/"))
        }, BorderLayout.CENTER)
    }

    private fun createOllamaPanel(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(5)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(JBLabel("Ollama URL: "))
            add(ollamaUrlField)
            add(Box.createHorizontalStrut(10))
            add(createHelpLink("https://ollama.ai/"))
        }, BorderLayout.CENTER)
    }

    private fun createHelpLink(url: String): JButton = JButton("?").apply {
        toolTipText = "Open $url"
        isFocusPainted = false
        addActionListener {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            } catch (ignored: Exception) {
                // Help-link failures must not break the settings panel.
            }
        }
    }

    private fun createClaudeCodeAgentPanel(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Binary path:"), claudeCodePathField)
        .addLabeledComponent(JBLabel("Extra CLI args:"), claudeCodeExtraArgsField)
        .addLabeledComponent(JBLabel("Model:"), claudeCodeModelField)
        .addLabeledComponent(JBLabel("Max output tokens:"), claudeCodeMaxOutputTokensField)
        .addLabeledComponent(JBLabel("Thinking budget:"), claudeCodeThinkingBudgetField)
        .addLabeledComponent(JBLabel("Effort:"), claudeCodeEffortCombo)
        .addLabeledComponent(JBLabel("Read timeout (sec):"), claudeCodeReadTimeoutField)
        .addLabeledComponent(JBLabel("Start timeout (sec):"), claudeCodeStartTimeoutField)
        .panel

    private fun createCodexAgentPanel(): JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Binary path:"), codexPathField)
        .addLabeledComponent(JBLabel("Extra app-server args:"), codexExtraArgsField)
        .addLabeledComponent(JBLabel("Model:"), codexModelField)
        .addLabeledComponent(JBLabel("Reasoning effort:"), codexReasoningEffortField)
        .addLabeledComponent(JBLabel("Read timeout (sec):"), codexReadTimeoutField)
        .addLabeledComponent(JBLabel("Start timeout (sec):"), codexStartTimeoutField)
        .panel

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

        claudeCodeAgentPanel = createClaudeCodeAgentPanel()
        codexAgentPanel = createCodexAgentPanel()
        val agentPanelsContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(claudeCodeAgentPanel)
            add(codexAgentPanel)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("LLM Provider:"), providerComboBox)
            .addComponent(providerPanelsContainer)
            .addSeparator()
            .addLabeledComponent(JBLabel("Model:"), modelPanel)
            .addLabeledComponent(JBLabel("Temperature:"), temperaturePanel)
            .addSeparator()
            .addComponent(JBLabel("Coding Agent (CLI mode):"))
            .addLabeledComponent(JBLabel("Provider:"), codingAgentProviderCombo)
            .addComponent(agentPanelsContainer)
            .addSeparator()
            .addComponent(mockFallbackCheckBox)
            .addComponent(testPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply { border = JBUI.Borders.empty(10) }
    }

    private fun setupListeners() {
        providerComboBox.addActionListener {
            updateProviderPanels()
            updateModelComboBox()
        }
        codingAgentProviderCombo.addActionListener { updateCodingAgentPanels() }
        temperatureSlider.addChangeListener {
            val temp = temperatureSlider.value / 100.0
            temperatureLabel.text = String.format("%.2f", temp)
        }
        customModelField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onCustomModelChanged()
            override fun removeUpdate(e: DocumentEvent?) = onCustomModelChanged()
            override fun changedUpdate(e: DocumentEvent?) = onCustomModelChanged()
        })
        testConnectionButton.addActionListener { testConnection() }
    }

    private fun onCustomModelChanged() {
        modelComboBox.isEnabled = customModelField.text.isBlank()
    }

    private fun updateProviderPanels() {
        val providerKey = getSelectedProviderKey()
        openAIPanel.isVisible = providerKey == "OPENAI"
        anthropicPanel.isVisible = providerKey == "ANTHROPIC"
        ollamaPanel.isVisible = providerKey == "OLLAMA"
    }

    /**
     * Hidden groups keep their values: a group is only invisible, never cleared,
     * so switching agents cannot silently drop the other agent's binary path.
     */
    private fun updateCodingAgentPanels() {
        val providerKey = getSelectedCodingAgentProviderKey()
        claudeCodeAgentPanel.isVisible = providerKey == "CLAUDE_CODE"
        codexAgentPanel.isVisible = providerKey == "CODEX"
    }

    private fun updateModelComboBox() {
        val models = MaxVibesSettings.DEFAULT_MODELS[getSelectedProviderKey()] ?: emptyList()
        modelComboBox.removeAllItems()
        models.forEach { model ->
            modelComboBox.addItem(model.second)
        }
        if (modelComboBox.itemCount > 0) {
            modelComboBox.selectedIndex = 0
        }
    }

    private fun getSelectedProviderKey(): String {
        val index = providerComboBox.selectedIndex
        return if (index >= 0) MaxVibesSettings.PROVIDERS[index].first else "ANTHROPIC"
    }

    private fun getSelectedCodingAgentProviderKey(): String =
        MaxVibesSettings.CODING_AGENT_PROVIDERS
            .getOrNull(codingAgentProviderCombo.selectedIndex)?.first ?: "CLAUDE_CODE"

    private fun getSelectedModelId(): String {
        if (customModelField.text.isNotBlank()) return customModelField.text.trim()
        val models = MaxVibesSettings.DEFAULT_MODELS[getSelectedProviderKey()] ?: emptyList()
        val index = modelComboBox.selectedIndex
        return if (index >= 0 && index < models.size) models[index].first else models.firstOrNull()?.first.orEmpty()
    }

    private fun testConnection() {
        statusLabel.text = "Testing..."
        testConnectionButton.isEnabled = false
        Thread {
            try {
                val service = LLMServiceFactory.create(createCurrentConfig())
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
        val modelId = getSelectedModelId()
        val temp = temperatureSlider.value / 100.0
        return when (val providerKey = getSelectedProviderKey()) {
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

    fun loadSettings(settings: MaxVibesSettings) {
        val providerIndex = MaxVibesSettings.PROVIDERS.indexOfFirst { it.first == settings.provider }
        if (providerIndex >= 0) providerComboBox.selectedIndex = providerIndex
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

        val codingAgentProviderIndex = MaxVibesSettings.CODING_AGENT_PROVIDERS
            .indexOfFirst { it.first == settings.codingAgentProvider }
        codingAgentProviderCombo.selectedIndex = codingAgentProviderIndex.coerceAtLeast(0)
        updateCodingAgentPanels()

        claudeCodePathField.text = settings.claudeCodePath
        claudeCodeExtraArgsField.text = settings.claudeCodeExtraArgs
        claudeCodeModelField.text = settings.claudeCodeModel
        claudeCodeMaxOutputTokensField.text = settings.claudeCodeMaxOutputTokens.toString()
        claudeCodeThinkingBudgetField.text = settings.claudeCodeThinkingBudget.toString()
        claudeCodeEffortCombo.selectedItem = settings.claudeCodeEffortLevel.ifBlank { "Auto" }
        claudeCodeReadTimeoutField.text = settings.claudeCodeReadTimeoutSec.toString()
        claudeCodeStartTimeoutField.text = settings.claudeCodeStartTimeoutSec.toString()

        codexPathField.text = settings.codexPath
        codexExtraArgsField.text = settings.codexExtraArgs
        codexModelField.text = settings.codexModel
        codexReasoningEffortField.text = settings.codexReasoningEffort
        codexReadTimeoutField.text = settings.codexReadTimeoutSec.toString()
        codexStartTimeoutField.text = settings.codexStartTimeoutSec.toString()

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

        settings.codingAgentProvider = getSelectedCodingAgentProviderKey()

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

        settings.codexPath = codexPathField.text.trim().ifBlank { "codex" }
        settings.codexExtraArgs = codexExtraArgsField.text
        settings.codexModel = codexModelField.text.trim()
        settings.codexReasoningEffort = codexReasoningEffortField.text.trim()
        settings.codexReadTimeoutSec =
            codexReadTimeoutField.text.trim().toIntOrNull()?.coerceAtLeast(1) ?: settings.codexReadTimeoutSec
        settings.codexStartTimeoutSec =
            codexStartTimeoutField.text.trim().toIntOrNull()?.coerceAtLeast(1) ?: settings.codexStartTimeoutSec
    }

    fun isModified(settings: MaxVibesSettings): Boolean {
        val selectedCodingAgentProvider = getSelectedCodingAgentProviderKey()
        val pathChanged = settings.claudeCodePath != claudeCodePathField.text.trim().ifBlank { "claude" }
        val argsChanged = settings.claudeCodeExtraArgs != claudeCodeExtraArgsField.text
        val claudeModelChanged = settings.claudeCodeModel != claudeCodeModelField.text.trim()
        val maxOutputChanged =
            settings.claudeCodeMaxOutputTokens.toString() != claudeCodeMaxOutputTokensField.text.trim()
        val thinkingBudgetChanged =
            settings.claudeCodeThinkingBudget.toString() != claudeCodeThinkingBudgetField.text.trim()
        val effortChanged = settings.claudeCodeEffortLevel.ifBlank { "Auto" } !=
                (claudeCodeEffortCombo.selectedItem as? String ?: "Auto")
        val readTimeoutChanged = settings.claudeCodeReadTimeoutSec.toString() != claudeCodeReadTimeoutField.text.trim()
        val startTimeoutChanged =
            settings.claudeCodeStartTimeoutSec.toString() != claudeCodeStartTimeoutField.text.trim()
        val codexPathChanged = settings.codexPath != codexPathField.text.trim().ifBlank { "codex" }
        val codexArgsChanged = settings.codexExtraArgs != codexExtraArgsField.text
        val codexModelChanged = settings.codexModel != codexModelField.text.trim()
        val codexEffortChanged = settings.codexReasoningEffort != codexReasoningEffortField.text.trim()
        val codexReadTimeoutChanged = settings.codexReadTimeoutSec.toString() != codexReadTimeoutField.text.trim()
        val codexStartTimeoutChanged = settings.codexStartTimeoutSec.toString() != codexStartTimeoutField.text.trim()

        return settings.provider != getSelectedProviderKey() ||
                settings.modelId != getSelectedModelId() ||
                settings.ollamaBaseUrl != ollamaUrlField.text ||
                settings.temperature != temperatureSlider.value / 100.0 ||
                settings.enableMockFallback != mockFallbackCheckBox.isSelected ||
                settings.openAIApiKey != String(openAIKeyField.password) ||
                settings.anthropicApiKey != String(anthropicKeyField.password) ||
                settings.codingAgentProvider != selectedCodingAgentProvider ||
                pathChanged || argsChanged || claudeModelChanged || maxOutputChanged ||
                thinkingBudgetChanged || effortChanged || readTimeoutChanged || startTimeoutChanged ||
                codexPathChanged || codexArgsChanged || codexModelChanged || codexEffortChanged ||
                codexReadTimeoutChanged || codexStartTimeoutChanged
    }
}
