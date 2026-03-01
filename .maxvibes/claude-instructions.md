This project uses MaxVibes IDE plugin clipboard protocol for code modifications.

When you receive a JSON message containing "_protocol", "systemInstruction", "task", "fileTree", or "files" fields — this is a MaxVibes protocol message from the IDE plugin. Follow these rules STRICTLY:

1. RESPOND WITH ONLY A JSON OBJECT. Your entire response must be valid JSON — no markdown, no text before/after, no code blocks, no explanations outside the JSON.

2. DO NOT use computer tools, bash, file creation, artifacts, or any other tools. ALL code must go inside the JSON response in the "modifications" array.

3. Response format:
{
    "message": "Your explanation or answer",
    "requestedFiles": ["path/to/file.kt"],
    "reasoning": "why you need those files",
    "modifications": [...]
}

4. All fields are optional except "message" (always recommended):
   - "message" — your explanation or discussion
   - "requestedFiles" — files you need to see (triggers file gathering in IDE)
   - "reasoning" — why you need those files
   - "modifications" — code changes (see types below)

5. Modification types — PREFER element-level for existing files:

   | type             | When                          | path format                                              | content              | extra fields          |
   |------------------|-------------------------------|----------------------------------------------------------|----------------------|-----------------------|
   | REPLACE_ELEMENT  | Change a function/class/prop  | file:path/File.kt/class[Name]/function[method]           | Complete element     | elementKind           |
   | CREATE_ELEMENT   | Add new element to parent     | file:path/File.kt/class[Name]                            | New element code     | elementKind, position |
   | DELETE_ELEMENT    | Remove an element             | file:path/File.kt/class[Name]/function[old]              | (empty)              |                       |
   | ADD_IMPORT        | Add import to file            | file:path/File.kt                                        | (empty)              | importPath            |
   | REMOVE_IMPORT     | Remove import from file       | file:path/File.kt                                        | (empty)              | importPath            |
   | CREATE_FILE       | New file                      | src/main/kotlin/.../File.kt                              | Full file            |                       |
   | REPLACE_FILE      | Rewrite entire file           | src/main/kotlin/.../File.kt                              | Full file            |                       |

6. Element path format:
   file:src/main/kotlin/com/example/User.kt/class[User]/function[validate]
   Segments: class[Name], interface[Name], object[Name], function[Name], property[Name], companion_object, init, constructor[primary], enum_entry[Name]

7. Key rules for modifications:
   - PREFER REPLACE_ELEMENT/CREATE_ELEMENT over REPLACE_FILE — saves tokens!
   - Only use REPLACE_FILE when the majority of the file changes
   - For REPLACE_ELEMENT: content = COMPLETE element (annotations, modifiers, signature, body)
   - For CREATE_ELEMENT: set elementKind and position (LAST_CHILD, FIRST_CHILD)
   - Use ADD_IMPORT/REMOVE_IMPORT for imports — never manually edit the import block
   - "content" must be complete, compilable Kotlin code

8. For regular conversation (not MaxVibes protocol messages), respond normally as usual.