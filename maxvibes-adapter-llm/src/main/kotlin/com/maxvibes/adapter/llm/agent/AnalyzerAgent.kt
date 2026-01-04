// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/agent/AnalyzerAgent.kt
package com.maxvibes.adapter.llm.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.maxvibes.application.port.output.AnalysisResponse
import com.maxvibes.domain.model.code.CodeElement

/**
 * AI агент для анализа Kotlin кода.
 * Отвечает на вопросы о структуре, логике и качестве кода.
 */
class AnalyzerAgent(
    private val promptExecutor: PromptExecutor,
    private val llmModel: LLModel,
    private val temperature: Double = 0.3
) {
    companion object {
        val SYSTEM_PROMPT = """
            You are an expert Kotlin code analyst integrated into an IntelliJ IDEA plugin.
            Your task is to analyze Kotlin code and answer questions about it.
            
            ## Your Capabilities
            
            - Explain code structure and logic
            - Identify potential issues and bugs
            - Suggest improvements and best practices
            - Answer questions about design patterns used
            - Explain complex code in simple terms
            - Review code quality and style
            
            ## Guidelines
            
            1. Be precise and specific in your analysis
            2. Reference specific code elements when discussing them
            3. Use Kotlin-specific terminology correctly
            4. Provide actionable suggestions when relevant
            5. If something is unclear, say so honestly
            6. Consider the context and purpose of the code
            
            ## Response Format
            
            - Start with a direct answer to the question
            - Provide supporting details and explanations
            - Include code examples when helpful
            - End with actionable suggestions if applicable
            
            Keep your responses focused and avoid unnecessary verbosity.
        """.trimIndent()
    }

    /**
     * Анализирует код и отвечает на вопрос пользователя
     */
    suspend fun analyzeCode(
        question: String,
        codeElements: List<CodeElement>
    ): AnalysisResponse {
        val userPrompt = buildAnalysisPrompt(question, codeElements)

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            systemPrompt = SYSTEM_PROMPT,
            llmModel = llmModel,
            temperature = temperature,
            maxIterations = 5 // Анализ обычно не требует много итераций
        )

        return try {
            val result = agent.run(userPrompt)

            // Парсим ответ и извлекаем suggestions если есть
            parseAnalysisResponse(result ?: "No response from LLM")
        } catch (e: Exception) {
            AnalysisResponse(
                answer = "Error during analysis: ${e.message}",
                suggestions = listOf("Try rephrasing your question", "Check if the code context is correct")
            )
        }
    }

    private fun buildAnalysisPrompt(question: String, codeElements: List<CodeElement>): String {
        return buildString {
            appendLine("## Code to Analyze")
            appendLine()

            if (codeElements.isEmpty()) {
                appendLine("No code provided for analysis.")
            } else {
                codeElements.forEach { element ->
                    appendLine("### ${element.kind.name}: ${element.name}")
                    appendLine("Path: `${element.path.value}`")
                    appendLine()
                    appendLine("```kotlin")
                    // Используем полный content для детального анализа
                    appendLine(element.content)
                    appendLine("```")
                    appendLine()

                    // Добавляем информацию о children если есть
                    if (element.children.isNotEmpty()) {
                        appendLine("Contains: ${element.children.map { "${it.kind.name} ${it.name}" }.joinToString(", ")}")
                        appendLine()
                    }
                }
            }

            appendLine("## Question")
            appendLine()
            appendLine(question)
        }
    }

    private fun parseAnalysisResponse(response: String): AnalysisResponse {
        // Пытаемся извлечь suggestions из ответа
        val suggestions = mutableListOf<String>()

        // Ищем паттерны типа "Suggestion:", "Recommendation:", "Consider:", "Tip:"
        val suggestionPatterns = listOf(
            Regex("(?i)suggestion[s]?:\\s*(.+?)(?=\\n|$)"),
            Regex("(?i)recommendation[s]?:\\s*(.+?)(?=\\n|$)"),
            Regex("(?i)consider:\\s*(.+?)(?=\\n|$)"),
            Regex("(?i)tip[s]?:\\s*(.+?)(?=\\n|$)")
        )

        suggestionPatterns.forEach { pattern ->
            pattern.findAll(response).forEach { match ->
                suggestions.add(match.groupValues[1].trim())
            }
        }

        // Также ищем пункты списка после "Suggestions" заголовка
        val suggestionsSection = Regex("(?i)##?\\s*suggestions?[\\s\\S]*?(?=##|$)")
            .find(response)?.value

        suggestionsSection?.let { section ->
            Regex("[-*]\\s+(.+)")
                .findAll(section)
                .map { it.groupValues[1].trim() }
                .forEach { suggestions.add(it) }
        }

        // Извлекаем referenced paths из ответа
        val referencedPaths = Regex("`([^`]*(?:file:|class\\[|function\\[|property\\[)[^`]*)`")
            .findAll(response)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        return AnalysisResponse(
            answer = response,
            suggestions = suggestions.distinct().take(5), // Максимум 5 suggestions
            referencedPaths = referencedPaths
        )
    }
}

/**
 * Extension для создания AnalyzerAgent
 */
fun createAnalyzerAgent(
    promptExecutor: PromptExecutor,
    llmModel: LLModel,
    temperature: Double = 0.3
): AnalyzerAgent {
    return AnalyzerAgent(promptExecutor, llmModel, temperature)
}