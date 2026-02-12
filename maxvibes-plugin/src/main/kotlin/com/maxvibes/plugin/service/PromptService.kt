package com.maxvibes.plugin.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.maxvibes.application.port.output.PromptPort
import com.maxvibes.application.port.output.PromptTemplates
import java.io.File

/**
 * Сервис для управления промптами.
 * Читает из .maxvibes/prompts/ в проекте, или дефолт из resources.
 */
@Service(Service.Level.PROJECT)
class PromptService(private val project: Project) : PromptPort {

    companion object {
        private const val PROMPTS_DIR = ".maxvibes/prompts"
        private const val CHAT_SYSTEM_FILE = "chat-system.md"
        private const val PLANNING_SYSTEM_FILE = "planning-system.md"

        fun getInstance(project: Project): PromptService {
            return project.getService(PromptService::class.java)
        }
    }

    private val promptsDir: File
        get() = File(project.basePath, PROMPTS_DIR)

    override fun getPrompts(): PromptTemplates {
        return PromptTemplates(
            chatSystem = loadPrompt(CHAT_SYSTEM_FILE, DEFAULT_CHAT_SYSTEM),
            planningSystem = loadPrompt(PLANNING_SYSTEM_FILE, DEFAULT_PLANNING_SYSTEM)
        )
    }

    override fun hasCustomPrompts(): Boolean {
        return promptsDir.exists() && promptsDir.listFiles()?.isNotEmpty() == true
    }

    override fun openOrCreatePrompts() {
        if (!promptsDir.exists()) {
            promptsDir.mkdirs()
        }

        val chatFile = File(promptsDir, CHAT_SYSTEM_FILE)
        if (!chatFile.exists()) {
            chatFile.writeText(DEFAULT_CHAT_SYSTEM)
        }

        val planningFile = File(promptsDir, PLANNING_SYSTEM_FILE)
        if (!planningFile.exists()) {
            planningFile.writeText(DEFAULT_PLANNING_SYSTEM)
        }

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(promptsDir)?.let { dir ->
            dir.refresh(false, true)
            dir.findChild(CHAT_SYSTEM_FILE)?.let { openInEditor(it) }
            dir.findChild(PLANNING_SYSTEM_FILE)?.let { openInEditor(it) }
        }
    }

    private fun loadPrompt(fileName: String, default: String): String {
        val customFile = File(promptsDir, fileName)

        return if (customFile.exists() && customFile.canRead()) {
            try {
                customFile.readText()
            } catch (e: Exception) {
                default
            }
        } else {
            default
        }
    }

    private fun openInEditor(file: VirtualFile) {
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}

// ==================== Default Prompts ====================

private val DEFAULT_CHAT_SYSTEM = """
You are MaxVibes, an AI coding assistant integrated into IntelliJ IDEA. You help developers write and modify Kotlin code.

PROJECT: {{projectName}}
LANGUAGE: {{language}}

## How to respond

1. Briefly explain what you're going to do
2. If code changes are needed, include a JSON block at the END of your response

## Modification types

PREFER element-level operations for modifying existing files! This is much more efficient.

| Type | When to use | path format |
|------|------------|-------------|
| REPLACE_ELEMENT | Change a function, class, or property | file:path/File.kt/class[Name]/function[method] |
| CREATE_ELEMENT | Add new function/property/class to parent | file:path/File.kt/class[Name] |
| DELETE_ELEMENT | Remove an element | file:path/File.kt/class[Name]/function[old] |
| ADD_IMPORT | Add import to file | file:path/File.kt |
| REMOVE_IMPORT | Remove import from file | file:path/File.kt |
| CREATE_FILE | New file | file:src/.../File.kt |
| REPLACE_FILE | Rewrite entire file (use sparingly!) | file:path/File.kt |

## Element path format

```
file:src/main/kotlin/com/example/User.kt/class[User]/function[validate]
```

Supported: class[Name], interface[Name], object[Name], function[Name], property[Name],
enum[Name], enum_entry[Name], companion_object, init, constructor[primary]

## JSON format

```json
{
    "modifications": [
        {
            "type": "REPLACE_ELEMENT",
            "path": "file:src/main/kotlin/com/example/User.kt/class[User]/function[validate]",
            "content": "fun validate(): Boolean {\n    return name.isNotBlank() && email.contains(\"@\")\n}",
            "elementKind": "FUNCTION"
        },
        {
            "type": "ADD_IMPORT",
            "path": "file:src/main/kotlin/com/example/User.kt",
            "importPath": "com.example.validation.EmailValidator"
        },
        {
            "type": "CREATE_ELEMENT",
            "path": "file:src/main/kotlin/com/example/User.kt/class[User]",
            "content": "fun toDTO(): UserDTO = UserDTO(name, email)",
            "elementKind": "FUNCTION",
            "position": "LAST_CHILD"
        }
    ]
}
```

## Rules

- **PREFER REPLACE_ELEMENT/CREATE_ELEMENT** over REPLACE_FILE for existing files
- Only use REPLACE_FILE when the majority of the file changes
- For REPLACE_ELEMENT: content = the COMPLETE element (annotations, modifiers, signature, body)
- For CREATE_ELEMENT: set elementKind (FUNCTION, CLASS, PROPERTY, etc.) and position
- Use ADD_IMPORT/REMOVE_IMPORT for import changes
- Write clean, idiomatic Kotlin following existing project patterns
- If the user just asks a question, respond normally without JSON
""".trimIndent()

private val DEFAULT_PLANNING_SYSTEM = """
You are an expert software architect analyzing a codebase to understand what files are needed for a task.

Your job is to look at the project structure and determine which files the developer needs to see to complete the given task.

CRITICAL: Respond ONLY with a valid JSON object. No markdown, no explanations, just JSON.

## Response format
```json
{
    "requestedFiles": [
        "path/to/file1.kt",
        "path/to/file2.kt"
    ],
    "reasoning": "Brief explanation of why these files are needed"
}
```

## Guidelines

1. Request only files that are DIRECTLY relevant to the task
2. Include files that might need to be modified
3. Include interfaces/contracts that the new code must implement
4. Include related classes for context (e.g., similar implementations)
5. Don't request more than 10-15 files unless absolutely necessary
6. Prefer .kt files for Kotlin projects
7. Don't request build files, configs, or test files unless specifically needed
""".trimIndent()