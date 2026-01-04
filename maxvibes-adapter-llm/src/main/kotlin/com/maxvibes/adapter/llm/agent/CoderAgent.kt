// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/agent/CoderAgent.kt
package com.maxvibes.adapter.llm.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.maxvibes.adapter.llm.agent.tools.CodeModificationTools
import com.maxvibes.application.port.output.LLMContext
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.modification.Modification
import kotlinx.coroutines.runBlocking

/**
 * AI агент для генерации модификаций Kotlin кода.
 * Использует Koog framework для взаимодействия с LLM.
 */
class CoderAgent(
    private val promptExecutor: PromptExecutor,
    private val llmModel: LLModel,
    private val temperature: Double = 0.2
) {
    companion object {
        /**
         * System prompt для агента - определяет его поведение
         */
        val SYSTEM_PROMPT = """
            You are an expert Kotlin developer assistant integrated into an IntelliJ IDEA plugin called MaxVibes.
            Your task is to modify Kotlin code based on user instructions.
            
            ## Your Capabilities
            
            You have access to tools for modifying code:
            - createElement: Add new classes, functions, properties to existing code
            - replaceElement: Replace existing code elements with new content
            - deleteElement: Remove code elements
            - createFile: Create new Kotlin files
            - replaceFile: Replace entire file content
            - deleteFile: Delete files
            - finishModifications: Signal that all modifications are complete
            
            ## Path Format (ElementPath)
            
            Paths follow this format: file:path/to/File.kt[/element[Name]]...
            
            Examples:
            - file:src/main/kotlin/User.kt - file level
            - file:src/main/kotlin/User.kt/class[User] - User class
            - file:src/main/kotlin/User.kt/class[User]/function[getName] - function inside User
            - file:src/main/kotlin/User.kt/class[User]/property[name] - property inside User
            - file:src/main/kotlin/User.kt/class[User]/class[Builder] - nested class
            
            ## Rules
            
            1. Generate VALID, COMPILABLE Kotlin code
            2. Preserve existing code style (indentation, naming conventions)
            3. Use MINIMAL, FOCUSED changes - don't modify more than necessary
            4. Always specify COMPLETE element content (full function/class/property)
            5. Use proper Kotlin idioms and best practices
            6. Add appropriate modifiers (public, private, override, etc.)
            7. Include necessary imports if creating new dependencies
            
            ## Workflow
            
            1. Analyze the provided code context
            2. Understand the user's instruction
            3. Plan the minimal set of modifications needed
            4. Execute the modifications using the available tools
            5. Call finishModifications when done
            
            ## Examples
            
            Instruction: "add toString method to User class"
            → Call createElement with:
               targetPath = "file:src/User.kt/class[User]"
               elementKind = "FUNCTION"
               content = "override fun toString(): String = \"User(name=${'$'}name, age=${'$'}age)\""
            → Call finishModifications
            
            Instruction: "rename function getData to fetchData"
            → Call replaceElement with the new function name
            → Call finishModifications
            
            Instruction: "add a companion object with a factory method"
            → Call createElement with elementKind = "OBJECT" (companion object)
            → Call finishModifications
        """.trimIndent()
    }

    private val codeTools = CodeModificationTools()

    /**
     * Генерирует модификации кода на основе инструкции пользователя
     */
    suspend fun generateModifications(
        instruction: String,
        context: LLMContext
    ): List<Modification> {
        // Очищаем предыдущие модификации
        codeTools.clearModifications()

        // Строим промпт с контекстом кода
        val userPrompt = buildUserPrompt(instruction, context)

        // Создаём агента с tools
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            systemPrompt = SYSTEM_PROMPT,
            llmModel = llmModel,
            toolRegistry = ToolRegistry {
                tools(codeTools.asTools())
            },
            temperature = temperature,
            maxIterations = 20 // Максимум 20 итераций для безопасности
        )

        // Запускаем агента
        try {
            agent.run(userPrompt)
        } catch (e: Exception) {
            // Логируем ошибку, но возвращаем что успели сгенерировать
            println("CoderAgent error: ${e.message}")
        }

        return codeTools.modifications
    }

    /**
     * Строит промпт для пользователя с контекстом кода
     */
    private fun buildUserPrompt(instruction: String, context: LLMContext): String {
        return buildString {
            appendLine("## Current Code Context")
            appendLine()

            if (context.relevantCode.isEmpty()) {
                appendLine("No code context provided.")
            } else {
                context.relevantCode.forEach { element ->
                    appendLine("### ${element.kind.name}: ${element.name}")
                    appendLine("Path: `${element.path.value}`")
                    appendLine()
                    appendLine("```kotlin")
                    appendLine(element.toCompactString())
                    appendLine("```")
                    appendLine()
                }
            }

            context.projectInfo?.let { info ->
                appendLine("## Project Info")
                appendLine("- Name: ${info.name}")
                appendLine("- Language: ${info.language}")
                appendLine()
            }

            context.additionalInstructions?.let { additional ->
                appendLine("## Additional Context")
                appendLine(additional)
                appendLine()
            }

            appendLine("## User Instruction")
            appendLine()
            appendLine(instruction)
            appendLine()
            appendLine("---")
            appendLine("Please analyze the code and execute the necessary modifications using the available tools.")
            appendLine("When done, call finishModifications to complete the task.")
        }
    }
}

/**
 * Extension для создания CoderAgent из конфигурации
 */
fun createCoderAgent(
    promptExecutor: PromptExecutor,
    llmModel: LLModel,
    temperature: Double = 0.2
): CoderAgent {
    return CoderAgent(promptExecutor, llmModel, temperature)
}