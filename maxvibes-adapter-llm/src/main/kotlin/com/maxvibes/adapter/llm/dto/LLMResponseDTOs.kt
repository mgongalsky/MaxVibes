// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/dto/LLMResponseDTOs.kt
package com.maxvibes.adapter.llm.dto

import dev.langchain4j.model.output.structured.Description

class ChatResponseDTO {
    @Description("Your response message to the user explaining what you did or answering their question")
    var message: String = ""

    @Description("Optional reasoning or step-by-step thoughts")
    var reasoning: String? = null

    @Description("List of code modifications to apply. Empty list if no code changes needed")
    var modifications: List<ModificationDTO> = emptyList()

    @Description("Optional: a concise Git commit message summarizing the changes made (in English, conventional commits format preferred, e.g. 'feat: add commit message generation'). Include when you've completed a coding task and there are actual code modifications, or when the user explicitly asks for a commit message. Leave null if no code changes were made.")
    var commitMessage: String? = null

    @Description("List of file/element views requested from the project. Each entry describes what was requested and at what granularity.")
    var requestedViews: List<RequestedViewInfoDTO> = emptyList()

    @Description("Shell commands to run in the user's terminal. LAST RESORT — only for what modifications cannot do (build, tests, git, dependencies, diagnostics). Never create/edit/delete source files via shell; sole exception is working around a PSI modification that just failed — state that in reason. Normally an empty list.")
    var commands: List<CommandDTO> = emptyList()
}

class ModificationDTO {
    @Description("""Type of modification. PREFER element-level operations for existing files:
- CREATE_FILE: Create a new file (content = full file with package/imports)
- REPLACE_ELEMENT: Replace a specific element in existing file (path points to the element, e.g. file:path/File.kt/class[Name]/function[method])
- CREATE_ELEMENT: Add a new element to a parent (path points to parent, e.g. file:path/File.kt/class[Name])
- DELETE_ELEMENT: Remove an element
- ADD_IMPORT: Add an import to a file (path = file path, importPath = FQ name like com.example.Foo)
- REMOVE_IMPORT: Remove an import from a file
- REPLACE_FILE: Replace entire file (use ONLY when most of the file changes)
- DELETE_FILE: Delete a file""")
    var type: String = ""

    @Description("""Element path. Format: file:relative/path/File.kt for file-level, or file:relative/path/File.kt/class[ClassName]/function[funcName] for elements.
Supported segments: class[Name], interface[Name], object[Name], function[Name], property[Name], enum[Name], enum_entry[Name], companion_object, init, constructor[primary]""")
    var path: String = ""

    @Description("Code content. For CREATE_FILE/REPLACE_FILE: complete file. For REPLACE_ELEMENT: complete element code. For CREATE_ELEMENT: new element code. For ADD_IMPORT/REMOVE_IMPORT: leave empty (use importPath instead)")
    var content: String = ""

    @Description("Kind of element: FILE, CLASS, INTERFACE, OBJECT, ENUM, FUNCTION, PROPERTY")
    var elementKind: String = "FILE"

    @Description("Insert position for CREATE_ELEMENT: LAST_CHILD, FIRST_CHILD, BEFORE, AFTER")
    var position: String = "LAST_CHILD"

    @Description("For ADD_IMPORT/REMOVE_IMPORT only: fully qualified import path, e.g. com.example.dto.UserDTO")
    var importPath: String = ""

    override fun toString(): String = "ModificationDTO(type=$type, path=$path, content=${content.take(50)}...)"
}

class RequestedViewInfoDTO {
    @Description("File path as used in the request (e.g. 'src/main/kotlin/com/example/Foo.kt')")
    var path: String = ""

    @Description("Granularity of the view: FULL, SIGNATURES, OUTLINE, or ELEMENT")
    var granularity: String = "FULL"

    @Description("Element path within the file, required when granularity is ELEMENT (e.g. 'class[Foo]/function[bar]')")
    var elementPath: String? = null
}

/**
 * DTO for planning phase response.
 * Now uses same message field as chat, no separate reasoning.
 */
class PlanningResultDTO {
    @Description("Your thoughts and explanation about what files you need and why")
    var message: String = ""

    @Description("List of file paths that need to be read for this task")
    var requestedFiles: List<String> = emptyList()

    override fun toString(): String = "PlanningResultDTO(files=${requestedFiles.size}, message=${message.take(50)})"
}

class CommandDTO {
    @Description("Shell command line to execute from the project root (e.g. 'gradlew.bat test')")
    var command: String = ""

    @Description("REQUIRED: one human-readable sentence explaining why this command is needed and why modifications cannot do it. Shown to the user for approval.")
    var reason: String? = null

    @Description("Timeout in seconds for this command (default 120)")
    var timeoutSec: Int = 120

    override fun toString(): String = "CommandDTO(command=${command.take(60)})"
}