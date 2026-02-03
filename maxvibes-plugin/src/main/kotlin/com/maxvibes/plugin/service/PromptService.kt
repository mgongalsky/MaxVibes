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
        // Создаём директорию если нет
        if (!promptsDir.exists()) {
            promptsDir.mkdirs()
        }

        // Создаём файлы с дефолтами если нет
        val chatFile = File(promptsDir, CHAT_SYSTEM_FILE)
        if (!chatFile.exists()) {
            chatFile.writeText(DEFAULT_CHAT_SYSTEM)
        }

        val planningFile = File(promptsDir, PLANNING_SYSTEM_FILE)
        if (!planningFile.exists()) {
            planningFile.writeText(DEFAULT_PLANNING_SYSTEM)
        }

        // Обновляем VFS и открываем файлы
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

1. First, explain what you're going to do in plain language
2. Describe what changes you made (files created, functions added, etc.)
3. If you need to create or modify code, include a JSON block at the END of your response

## Response format

Write naturally, like a helpful colleague. Be concise but informative.

After your explanation, if there are code changes, add:
```json
{
    "modifications": [
        {
            "type": "CREATE_FILE" | "REPLACE_FILE",
            "path": "src/main/kotlin/com/example/File.kt",
            "content": "full file content"
        }
    ]
}
```

## Rules for code

- Always include package declaration and imports
- Write clean, idiomatic Kotlin
- Follow existing project patterns
- For new files: use CREATE_FILE
- For changing existing files: use REPLACE_FILE with complete new content

If the user just asks a question or wants to chat, respond normally without JSON.
If the user asks to modify code, explain what you'll do, then include the JSON.
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