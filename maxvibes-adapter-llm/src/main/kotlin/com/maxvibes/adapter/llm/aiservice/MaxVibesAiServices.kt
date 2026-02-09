// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/aiservice/MaxVibesAiServices.kt
package com.maxvibes.adapter.llm.aiservice

import com.maxvibes.adapter.llm.dto.ChatResponseDTO
import com.maxvibes.adapter.llm.dto.PlanningResultDTO
import dev.langchain4j.service.UserMessage
import dev.langchain4j.service.V

/**
 * AiService interface for the planning phase.
 * LangChain4j creates a proxy that handles:
 * - Converting PlanningResultDTO to JSON Schema
 * - Sending schema via tool calling to LLM
 * - Parsing LLM's tool call response back to PlanningResultDTO
 *
 * System prompt is injected via chatRequestTransformer (dynamic, from .maxvibes/prompts/).
 */
interface PlanningAiService {

    @UserMessage("""
=== TASK ===
{{task}}

=== PROJECT INFO ===
Name: {{projectName}}
Root: {{rootPath}}
Tech stack: {{language}}, {{buildTool}}
{{frameworksLine}}

{{descriptionBlock}}

{{architectureBlock}}

=== FILE TREE ===
{{fileTree}}

Based on the task and project structure, which files do I need to see?
""")
    fun planContext(
        @V("task") task: String,
        @V("projectName") projectName: String,
        @V("rootPath") rootPath: String,
        @V("language") language: String,
        @V("buildTool") buildTool: String,
        @V("frameworksLine") frameworksLine: String,
        @V("descriptionBlock") descriptionBlock: String,
        @V("architectureBlock") architectureBlock: String,
        @V("fileTree") fileTree: String
    ): PlanningResultDTO
}

/**
 * AiService interface for the chat phase.
 * Returns structured ChatResponseDTO with both message text and modifications.
 *
 * System prompt, history, and file context are injected via chatRequestTransformer.
 * The @UserMessage here is just the current user message — all context is prepended
 * by the transformer.
 */
interface ChatAiService {

    fun chat(@UserMessage userMessage: String): ChatResponseDTO
}