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
 * Читает из .maxvibes/prompts/ в проекте, или дефолт из resources/inline.
 */
@Service(Service.Level.PROJECT)
class PromptService(private val project: Project) : PromptPort {

    companion object {
        private const val PROMPTS_DIR = ".maxvibes/prompts"
        fun getInstance(project: Project): PromptService {
            return project.getService(PromptService::class.java)
        }
    }

    /** Supplies the dynamic "## Skills" section appended to the Claude Code system prompt. Wired by MaxVibesService. */
    var skillCatalogProvider: (() -> String?)? = null

    private val promptsDir: File
        get() = File(project.basePath, PROMPTS_DIR)

    override fun getPrompts(): PromptTemplates = PromptTemplates(
        chatSystem = layers.compose(PromptKind.CHAT_SYSTEM),
        planningSystem = layers.compose(PromptKind.PLANNING_SYSTEM)
    )

    /**
     * Дополняет ли проект хоть один промпт.
     *
     * Проверяется содержимое слоя, а не наличие файлов: в новой схеме каталог непустой
     * всегда — там лежат зеркало базы и заглушки, — и проверка по файлам отвечала бы
     * «да» в проекте, где ничего не настраивали.
     */
    override fun hasCustomPrompts(): Boolean =
        PromptKind.values().any { layers.hasOverlay(it) }

    override fun openOrCreatePrompts() {
        resyncPrompts(archiveLegacy = false)

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(promptsDir)?.let { dir ->
            dir.refresh(false, true)
            // Открываются именно слои: база в base/ доступна для чтения, но её правка
            // ни на что не влияет, и предлагать её к редактированию нельзя.
            PromptKind.values().forEach { kind ->
                dir.findChild(kind.localFileName)?.let { openInEditor(it) }
            }
        }
    }

    override fun claudeCodeSystem(): String = codingAgentPrompt(PromptKind.CLAUDE_CODE_SYSTEM)

    override fun codexSystem(): String = codingAgentPrompt(PromptKind.CODEX_SYSTEM)

    private fun loadResource(path: String): String? {
        return PromptService::class.java.getResourceAsStream(path)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
    }

    private fun openInEditor(file: VirtualFile) {
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /**
     * Human-readable OS + shell descriptor for prompt substitution ({{os}}).
     * Example: "Windows (PowerShell)" / "macOS (sh)" / "Linux (sh)".
     */
    fun osDescriptor(): String = when {
        com.intellij.openapi.util.SystemInfo.isWindows -> "Windows (PowerShell)"
        com.intellij.openapi.util.SystemInfo.isMac -> "macOS (sh)"
        else -> "Linux (sh)"
    }
    private val layers: PromptLayers
        get() = PromptLayers(promptsDir) { baseText(it) }

    /**
     * Полный базовый текст промпта — то, что уедет модели, если проект ничего не дополняет.
     *
     * [INIT_BLOCK_CAPABILITY] входит именно сюда, а не приклеивается к результату сборки:
     * слой проекта обязан оставаться последним, иначе наша же поправка окажется после
     * пользовательских правил и молча их перебьёт.
     */
    private fun baseText(kind: PromptKind): String = when (kind) {
        PromptKind.CHAT_SYSTEM -> DEFAULT_CHAT_SYSTEM + INIT_BLOCK_CAPABILITY
        PromptKind.PLANNING_SYSTEM -> DEFAULT_PLANNING_SYSTEM
        PromptKind.CLAUDE_CODE_SYSTEM, PromptKind.CODEX_SYSTEM ->
            (loadResource(kind.resourcePath)
                ?: error("Missing classpath resource: ${kind.resourcePath}")) + INIT_BLOCK_CAPABILITY
    }

    /** Промпт кодинг-агента: база, слой проекта, затем каталог скиллов. */
    private fun codingAgentPrompt(kind: PromptKind): String = buildString {
        append(layers.compose(kind))
        skillCatalogProvider?.invoke()?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine()
            append(it)
        }
    }

    internal fun resyncPrompts(archiveLegacy: Boolean): PromptSyncReport {
        if (!promptsDir.exists()) promptsDir.mkdirs()
        val report = layers.sync(archiveLegacy)
        // Файлы пишутся мимо VFS, и без явного обновления IDE покажет их не сразу —
        // из настроек это выглядело бы как «кнопка ничего не сделала».
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(promptsDir)?.refresh(false, true)
        return report
    }

    /** Промпты старой схемы, которые больше не читаются. Пустой список — всё в порядке. */
    internal fun legacyPromptFiles(): List<File> = layers.legacyFiles()
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

## Terminal commands (LAST RESORT)

Environment: {{os}}. Commands run from the project root.

You may ask the IDE to run shell commands via a top-level "commands" field in your JSON (next to "modifications"):

"commands": [
    { "command": "gradlew.bat test", "reason": "run the tests after the changes", "timeoutSec": 300 }
]

Rules:
- ONLY for what modifications cannot do: build, tests, git, dependency management, diagnostics.
- Do NOT create, edit or delete source files via shell — use "modifications". Sole exception: a PSI modification just failed and you are working around it — say so explicitly in "reason".
- "reason" is REQUIRED — one human-readable sentence; the user approves or declines each command.
- Commands run AFTER modifications are applied, sequentially, stopping at the first non-zero exit code.
- Results (exit code + output tail) or the user's decline arrive in the next message — react to them; never silently retry a declined command.
""".trimIndent()

private val DEFAULT_PLANNING_SYSTEM = """
⚠️ CRITICAL: This is a MaxVibes clipboard protocol message. You MUST respond with ONLY a JSON object.
DO NOT use computer tools, bash, artifacts. Your ENTIRE response = one JSON object.

You are an expert software architect in a clipboard-based dialog through MaxVibes IDE plugin.

TASK: Analyze the task and project file tree, decide what files you need.

Respond with EXACTLY this JSON (nothing else):
{
    "message": "Your thoughts and explanation about what files you need and why",
    "requestedFiles": ["path/to/file.kt", ...],
    "reasoning": "Why you need these specific files"
}

Rules:
- "message" is REQUIRED
- "requestedFiles" — list files to read. Empty [] if you just want to discuss.
- DO NOT wrap JSON in markdown. Raw JSON only.
""".trimIndent()
private val INIT_BLOCK_CAPABILITY = """

## Kotlin init blocks — current capability (overrides older limitations above)

- `REPLACE_ELEMENT` supports replacing a complete Kotlin `init { ... }` block.
- Read it with `ELEMENT` and `elementPath: "class[Name]/init"`; for additional blocks use `init[1]`, `init[2]`, etc.
- Replace it with path `file:.../File.kt/class[Name]/init` (or `init[index]`) and content containing exactly one complete `init { ... }` block.
- `CREATE_ELEMENT` supports adding a complete init block to a class with `elementKind: "INIT"` and a normal position.
- There is no `REPLACE_TEXT`, `call[...]`, `initializer[...]`, or `whenEntry[...]` selector. To change an expression inside init, replace the containing init block as a whole.
- Constructors remain unsupported by element replacement; use `REPLACE_FILE` for constructor structure changes.
""".trimIndent()