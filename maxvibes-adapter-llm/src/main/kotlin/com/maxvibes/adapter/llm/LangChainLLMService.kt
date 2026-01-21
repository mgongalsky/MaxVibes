package com.maxvibes.adapter.llm

import com.maxvibes.adapter.llm.config.LLMProviderConfig
import com.maxvibes.adapter.llm.config.LLMProviderType
import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.shared.result.Result
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Duration

/**
 * LLM Service implementation using LangChain4j.
 * No Ktor dependencies - uses OkHttp internally, no conflicts with IntelliJ.
 */
class LangChainLLMService(
    private val config: LLMProviderConfig
) : LLMService {

    private val chatModel: ChatLanguageModel by lazy { createChatModel() }

    private fun createChatModel(): ChatLanguageModel {
        return when (config.providerType) {
            LLMProviderType.OPENAI -> OpenAiChatModel.builder()
                .apiKey(config.apiKey)
                .modelName(resolveOpenAIModel(config.modelId))
                .temperature(config.temperature)
                .maxTokens(config.maxTokens)
                .timeout(Duration.ofSeconds(120))
                .build()

            LLMProviderType.ANTHROPIC -> AnthropicChatModel.builder()
                .apiKey(config.apiKey)
                .modelName(resolveAnthropicModel(config.modelId))
                .temperature(config.temperature)
                .maxTokens(config.maxTokens)
                .timeout(Duration.ofSeconds(120))
                .build()

            LLMProviderType.OLLAMA -> OllamaChatModel.builder()
                .baseUrl(config.baseUrl ?: "http://localhost:11434")
                .modelName(config.modelId)
                .temperature(config.temperature)
                .timeout(Duration.ofSeconds(300))
                .build()
        }
    }

    private fun resolveOpenAIModel(modelId: String): String = when (modelId) {
        "gpt-5.2", "gpt5", "gpt-5" -> "gpt-4o"  // fallback
        "gpt-4o" -> "gpt-4o"
        "gpt-4o-mini" -> "gpt-4o-mini"
        "gpt-4-turbo" -> "gpt-4-turbo"
        "gpt-4" -> "gpt-4"
        "gpt-3.5-turbo" -> "gpt-3.5-turbo"
        else -> modelId
    }

    private fun resolveAnthropicModel(modelId: String): String = when (modelId) {
        "claude-sonnet-4-5", "claude-4-sonnet" -> "claude-sonnet-4-20250514"
        "claude-sonnet-4" -> "claude-sonnet-4-20250514"
        "claude-3-opus" -> "claude-3-opus-20240229"
        "claude-3-sonnet" -> "claude-3-sonnet-20240229"
        "claude-3-haiku" -> "claude-3-haiku-20240307"
        else -> modelId
    }

    // ==================== LLMService Implementation ====================

    override suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): Result<List<Modification>, LLMError> = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = buildModificationSystemPrompt()
            val userPrompt = buildModificationUserPrompt(instruction, context)

            println("[LangChainLLMService] Provider: ${config.providerType}, Model: ${config.modelId}")
            println("[LangChainLLMService] Instruction: $instruction")

            val response = chatModel.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
            )

            val content = response.content().text()
            println("[LangChainLLMService] Response length: ${content.length}")

            val modifications = parseModifications(content)

            if (modifications.isEmpty()) {
                println("[LangChainLLMService] Warning: No modifications parsed")
            } else {
                println("[LangChainLLMService] Parsed ${modifications.size} modification(s)")
            }

            Result.Success(modifications)

        } catch (e: Exception) {
            println("[LangChainLLMService] Error: ${e.message}")
            e.printStackTrace()
            Result.Failure(LLMError.NetworkError(e.message ?: "Unknown error"))
        }
    }

    override suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): Result<AnalysisResponse, LLMError> = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = """
                You are an expert code analyst. Analyze the provided code and answer questions about it.
                Be concise but thorough. Provide actionable suggestions when relevant.
            """.trimIndent()

            val userPrompt = buildString {
                appendLine("Question: $question")
                appendLine()
                appendLine("Code to analyze:")
                codeElements.forEach { element ->
                    appendLine("--- ${element.kind}: ${element.name} ---")
                    appendLine(element.content)
                    appendLine()
                }
            }

            val response = chatModel.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
            )

            val answer = response.content().text()

            Result.Success(
                AnalysisResponse(
                    answer = answer,
                    suggestions = extractSuggestions(answer),
                    referencedPaths = emptyList()
                )
            )

        } catch (e: Exception) {
            Result.Failure(LLMError.NetworkError(e.message ?: "Analysis failed"))
        }
    }

    // ==================== Prompt Building ====================

    private fun buildModificationSystemPrompt(): String = """
        You are an expert Kotlin developer. Your task is to generate code modifications based on user instructions.
        
        CRITICAL: Respond ONLY with a valid JSON object. No markdown, no explanations, just JSON.
        
        Response format:
        {
            "modifications": [
                {
                    "type": "CREATE_FILE" | "REPLACE_FILE" | "CREATE_ELEMENT" | "REPLACE_ELEMENT" | "DELETE_ELEMENT",
                    "path": "src/main/kotlin/com/example/FileName.kt",
                    "elementKind": "FILE" | "CLASS" | "FUNCTION" | "PROPERTY",
                    "content": "full code content here",
                    "position": "LAST_CHILD"
                }
            ]
        }
        
        Rules:
        1. For new files: use "CREATE_FILE" with complete file content including package and imports
        2. For modifying existing code: use "REPLACE_FILE" or "REPLACE_ELEMENT"
        3. Path must be a valid file path (e.g., "src/main/kotlin/com/example/MyClass.kt")
        4. Content must be complete, compilable Kotlin code
        5. Always include package declaration and necessary imports
        6. Write clean, idiomatic Kotlin code following best practices
    """.trimIndent()

    private fun buildModificationUserPrompt(instruction: String, context: LLMContext): String = buildString {
        appendLine("=== USER INSTRUCTION ===")
        appendLine(instruction)
        appendLine()

        context.projectInfo?.let { info ->
            appendLine("=== PROJECT INFO ===")
            appendLine("Name: ${info.name}")
            appendLine("Language: ${info.language}")
            appendLine()
        }

        if (context.relevantCode.isNotEmpty()) {
            appendLine("=== EXISTING CODE CONTEXT ===")
            context.relevantCode.forEach { element ->
                appendLine("--- ${element.kind}: ${element.name} (${element.path.value}) ---")
                appendLine(element.content)
                appendLine()
            }
        } else {
            appendLine("=== NO EXISTING CODE ===")
            appendLine("Create new file(s) as needed.")
            appendLine()
        }

        context.additionalInstructions?.let {
            appendLine("=== ADDITIONAL INSTRUCTIONS ===")
            appendLine(it)
            appendLine()
        }

        appendLine("Now generate the JSON with modifications.")
    }

    // ==================== Response Parsing ====================

    private fun parseModifications(response: String): List<Modification> {
        return try {
            val jsonContent = extractJson(response)
            val jsonObject = Json.parseToJsonElement(jsonContent).jsonObject
            val modificationsArray = jsonObject["modifications"]?.jsonArray ?: return emptyList()

            modificationsArray.mapNotNull { element ->
                parseModificationElement(element.jsonObject)
            }
        } catch (e: Exception) {
            println("[LangChainLLMService] JSON parse failed: ${e.message}")
            println("[LangChainLLMService] Trying markdown fallback...")
            extractCodeFromMarkdown(response)
        }
    }

    private fun extractJson(response: String): String {
        // Remove markdown code blocks if present
        var cleaned = response.trim()
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.removePrefix("```json").removeSuffix("```").trim()
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```").removeSuffix("```").trim()
        }

        // Find JSON object boundaries
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')

        return if (start >= 0 && end > start) {
            cleaned.substring(start, end + 1)
        } else {
            cleaned
        }
    }

    private fun parseModificationElement(json: JsonObject): Modification? {
        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val path = json["path"]?.jsonPrimitive?.contentOrNull ?: return null
        val content = json["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val elementKindStr = json["elementKind"]?.jsonPrimitive?.contentOrNull ?: "FILE"
        val positionStr = json["position"]?.jsonPrimitive?.contentOrNull ?: "LAST_CHILD"

        val elementPath = ElementPath(path)
        val elementKind = try {
            ElementKind.valueOf(elementKindStr.uppercase())
        } catch (e: Exception) {
            ElementKind.FILE
        }
        val position = try {
            InsertPosition.valueOf(positionStr.uppercase())
        } catch (e: Exception) {
            InsertPosition.LAST_CHILD
        }

        return when (type.uppercase()) {
            "CREATE_FILE" -> Modification.CreateFile(
                targetPath = elementPath,
                content = content
            )
            "REPLACE_FILE" -> Modification.ReplaceFile(
                targetPath = elementPath,
                newContent = content
            )
            "DELETE_FILE" -> Modification.DeleteFile(
                targetPath = elementPath
            )
            "CREATE_ELEMENT" -> Modification.CreateElement(
                targetPath = elementPath,
                elementKind = elementKind,
                content = content,
                position = position
            )
            "REPLACE_ELEMENT" -> Modification.ReplaceElement(
                targetPath = elementPath,
                newContent = content
            )
            "DELETE_ELEMENT" -> Modification.DeleteElement(
                targetPath = elementPath
            )
            else -> null
        }
    }

    private fun extractCodeFromMarkdown(response: String): List<Modification> {
        val codeBlockRegex = Regex("```(?:kotlin|java)?\\s*\\n([\\s\\S]*?)```")
        val matches = codeBlockRegex.findAll(response)

        return matches.mapNotNull { match ->
            val code = match.groupValues[1].trim()
            if (code.isNotBlank()) {
                val fileName = extractFileNameFromCode(code)
                val path = "src/main/kotlin/$fileName"

                Modification.CreateFile(
                    targetPath = ElementPath(path),
                    content = code
                )
            } else null
        }.toList()
    }

    private fun extractFileNameFromCode(code: String): String {
        // Try to extract class/object name
        val classMatch = Regex("(?:class|object|interface|enum class)\\s+(\\w+)").find(code)
        if (classMatch != null) {
            return "${classMatch.groupValues[1]}.kt"
        }

        // Try to extract from package + main function
        val packageMatch = Regex("package\\s+([\\w.]+)").find(code)
        if (packageMatch != null) {
            val parts = packageMatch.groupValues[1].split(".")
            return "${parts.last().replaceFirstChar { it.uppercase() }}Main.kt"
        }

        return "GeneratedCode.kt"
    }

    private fun extractSuggestions(text: String): List<String> {
        val suggestions = mutableListOf<String>()
        val lines = text.lines()

        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("-") || trimmed.startsWith("•") || trimmed.startsWith("*")) {
                val suggestion = trimmed.removePrefix("-").removePrefix("•").removePrefix("*").trim()
                if (suggestion.length in 10..200) {
                    suggestions.add(suggestion)
                }
            }
        }

        return suggestions.take(5)
    }

    // ==================== Info ====================

    fun getProviderInfo(): String {
        return "${config.providerType} / ${config.modelId}"
    }
}