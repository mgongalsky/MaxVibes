This project uses MaxVibes IDE plugin clipboard protocol for code modifications.

When you receive a JSON message containing "_protocol", "systemInstruction", "task", "fileTree", or "files" fields — this is a MaxVibes protocol message from the IDE plugin. Follow these rules STRICTLY:

1. RESPOND WITH ONLY A JSON OBJECT. Your entire response must be valid JSON — no markdown, no text before/after, no code blocks, no explanations outside the JSON.

2. DO NOT use computer tools, bash, file creation, artifacts, or any other tools. ALL code must go inside the JSON response in the "modifications" array.

3. Response format:
{
"message": "Your explanation or answer",
"requestedFiles": ["path/to/file.kt"],
"reasoning": "why you need those files",
"commitMessage": "feat: optional git commit message",
"modifications": [...]
}

4. All fields are optional except "message" (always recommended):
- "message" — your explanation or discussion
- "requestedFiles" — files you need to see (triggers file gathering in IDE)
- "reasoning" — why you need those files
- "modifications" — code changes (see types below)
- "commitMessage" — optional Git commit message (see below)

5. Plan-only mode:
If the request contains `planOnly: true` or the user asks to discuss/plan — respond with a **text discussion only** in the "message" field. Do NOT include a "modifications" array. Talk through the approach, trade-offs, and implementation steps. This is for collaborative planning before writing code.

6. Commit messages:
You may include `"commitMessage"` with a concise English Git commit message (conventional commits preferred: `feat:`, `fix:`, `refactor:`, `chore:` etc.). The plugin automatically inserts it into the IDE commit dialog.
Include `commitMessage` when:
- You made actual code modifications (modifications array is non-empty)
- OR the user explicitly asks for a commit message
Omit it for planning discussions or when no code changes were made.

7. Modification types — PREFER element-level for existing files:

| type             | When                          | path format                                              | content              | extra fields          |
|------------------|-------------------------------|----------------------------------------------------------|----------------------|-----------------------|
| REPLACE_ELEMENT  | Change a function/class/prop  | file:path/File.kt/class[Name]/function[method]           | Complete element     | elementKind           |
| CREATE_ELEMENT   | Add new element               | see positioning rules below                              | New element code     | elementKind, position |
| DELETE_ELEMENT   | Remove an element             | file:path/File.kt/class[Name]/function[old]              | (empty)              |                       |
| ADD_IMPORT       | Add import to file            | file:path/File.kt                                        | (empty)              | importPath            |
| REMOVE_IMPORT    | Remove import from file       | file:path/File.kt                                        | (empty)              | importPath            |
| CREATE_FILE      | New file                      | src/main/kotlin/.../File.kt                              | Full file            |                       |
| REPLACE_FILE     | Rewrite entire file           | src/main/kotlin/.../File.kt                              | Full file            |                       |

8. Element path format:
file:src/main/kotlin/com/example/User.kt/class[User]/function[validate]
Segments: class[Name], interface[Name], object[Name], function[Name], property[Name], companion_object, init, constructor[primary], enum_entry[Name]

9. CREATE_ELEMENT positioning rules:

To add to end/start of a class — path points to the CLASS:
{
"type": "CREATE_ELEMENT",
"path": "file:src/main/kotlin/com/example/ChatPanel.kt/class[ChatPanel]",
"content": "fun updateTokenDisplay() { ... }",
"elementKind": "FUNCTION",
"position": "LAST_CHILD"
}

To insert after/before a specific element — path points to THAT ELEMENT:
{
"type": "CREATE_ELEMENT",
"path": "file:src/main/kotlin/com/example/ChatPanel.kt/class[ChatPanel]/property[statusLabel]",
"content": "private val tokenLabel = JBLabel(\"\")",
"elementKind": "PROPERTY",
"position": "AFTER"
}

NEVER use "anchor" field — it does not exist and will be silently ignored.

10. Key rules for modifications:
- PREFER REPLACE_ELEMENT/CREATE_ELEMENT over REPLACE_FILE — this saves tokens significantly
- Only use REPLACE_FILE when the majority of the file changes
- Only use CREATE_FILE for genuinely new files
- For REPLACE_ELEMENT: content = COMPLETE element (annotations, modifiers, signature, body)
- For CREATE_ELEMENT with position LAST_CHILD/FIRST_CHILD: path = parent class or file
- For CREATE_ELEMENT with position AFTER/BEFORE: path = the sibling element itself, NOT the parent
- Use ADD_IMPORT/REMOVE_IMPORT for imports — never manually edit the import block
- "content" must be complete, compilable Kotlin code
- The IDE applies changes automatically via PSI API

11. For regular conversation (not MaxVibes protocol messages), respond normally as usual.