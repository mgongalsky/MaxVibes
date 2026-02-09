// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/dto/LLMResponseDTOs.kt
package com.maxvibes.adapter.llm.dto

import dev.langchain4j.model.output.structured.Description

/**
 * DTO for structured chat response from LLM via AiServices tool calling.
 * LangChain4j converts this to JSON Schema automatically.
 *
 * IMPORTANT: Use regular classes with mutable fields (not Kotlin data classes)
 * for best compatibility with LangChain4j's JSON schema generation.
 */
class ChatResponseDTO {
    @Description("Your response message to the user explaining what you did or answering their question")
    var message: String = ""

    @Description("List of code modifications to apply. Empty list if no code changes needed")
    var modifications: List<ModificationDTO> = emptyList()
}

class ModificationDTO {
    @Description("Type of modification: CREATE_FILE, REPLACE_FILE, DELETE_FILE, CREATE_ELEMENT, REPLACE_ELEMENT, DELETE_ELEMENT")
    var type: String = ""

    @Description("File path relative to project root, e.g. src/main/kotlin/com/example/MyClass.kt")
    var path: String = ""

    @Description("Full code content for the file or element. Must be complete, compilable code with package and imports")
    var content: String = ""

    @Description("Kind of code element: FILE, CLASS, FUNCTION, PROPERTY")
    var elementKind: String = "FILE"

    @Description("Where to insert: LAST_CHILD, FIRST_CHILD, BEFORE, AFTER")
    var position: String = "LAST_CHILD"

    override fun toString(): String = "ModificationDTO(type=$type, path=$path, content=${content.take(50)}...)"
}

/**
 * DTO for planning phase response.
 */
class PlanningResultDTO {
    @Description("List of file paths that need to be read for this task")
    var requestedFiles: List<String> = emptyList()

    @Description("Brief explanation of why these files are needed")
    var reasoning: String? = null

    override fun toString(): String = "PlanningResultDTO(files=${requestedFiles.size}, reasoning=${reasoning?.take(50)})"
}