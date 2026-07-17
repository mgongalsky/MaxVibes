# Claude Code : Tier 1 refactorings never used by the model

## Symptom
Rename request (Tanks -> ScorchedEarth, session 46 dd35df) — the model emulates the rename:
CREATE_FILE with the renamed class + REPLACE_ELEMENT in Launcher +shell `Remove-Item` for the
old file . Its thinking states explicitly: "I can't rename files via PSI modifications directly".

## Root causes
        1.* * Prompt gap * * — the system prompt documents only REPLACE_ELEMENT / CREATE_ELEMENT /
        DELETE_ELEMENT / ADD_IMPORT / REMOVE_IMPORT / CREATE_FILE / REPLACE_FILE.RENAME_ELEMENT / SAFE_DELETE / MOVE_ELEMENT are absent, so the model cannot know they exist.Codec side is already ready: `parseModification` passes `type` through as a raw string and
already reads `newName` and `destination`.The validator has no type gate either.2.* * Prompt lifecycle * * — `ClaudeCodeProcessAdapter.ensureStarted` skipped respawn while the
process was alive, and `SpawnConfig` did not include the system prompt, so an edited prompt
        file never reached a running process (log: `systemPromptIgnored=true`).
FIXED: `systemPromptHash` added to `SpawnConfig`; prompt change triggers respawn with-- resume .
3.* * Protocol gap * * — there is no DELETE_FILE operation; even a Tier - 1 - aware model must shell out
to delete a file . Check whether SAFE_DELETE on the only top -level class of a file also removes
        the file (IntelliJ's SafeDeleteProcessor does for the sole class); otherwise add DELETE_FILE.

## Ready - to - paste prompt section(insert right after the "Modification types" table)

## Tier 1 — native IDE refactorings(STRONGLY PREFERRED when applicable)

These invoke IntelliJ's own refactoring engine. The IDE updates the declaration, ALL usages,
imports, and(for a top -level class) the file name across the whole project.

| Type           | What it does                                                            | Required fields |
|----------------|------------------------------------------------------------------------ - |------------------ - |
| RENAME_ELEMENT | Renames element +every usage; renames the file for a top -level class   | path, newName     |
| SAFE_DELETE    | Deletes element; returns explicit Failure listing usages if any remain | path |
| MOVE_ELEMENT   | Moves element to a new location, rewriting imports and references | path, destination |

Example — rename a class(this also renames TanksGame. kt and fixes every reference in other files):

```json
{
    "type": "RENAME_ELEMENT",
    "path": "file:src/main/kotlin/TanksGame.kt/class[TanksGame]",
    "newName": "ScorchedEarthGame"
}
```

Rules:
-ANY rename = one RENAME_ELEMENT.NEVER emulate a rename with CREATE_FILE +delete,
REPLACE_FILE, or shell commands.
-Renames need NO file content — do not request FULL or SIGNATURES views just to rename;
the element path is enough.
-RENAME_ELEMENT does not touch string literals or UI labels; fix those with a separate
small REPLACE_ELEMENT afterwards.
-Element deletion = SAFE_DELETE, never shell `rm` / `Remove-Item`.

## Verify before finalizing the section
-Exact accepted `type` strings in Modification . kt / the InteractionModification -> Modification
mapper(expected: RENAME_ELEMENT, SAFE_DELETE, MOVE_ELEMENT — codec does not constrain them).
-`destination` format for MOVE_ELEMENT(target file path? package?) in PsiRefactoringExecutor.
-SAFE_DELETE behavior when the target is a file's only top-level class (does the file go too?).

## Test
Rebuild plugin -> fresh Claude Code session in testVibes -> ask to rename ScorchedEarthGame to
ArtilleryGame.Expect a single RENAME_ELEMENT (plus optionally one small REPLACE_ELEMENT for the
launcher label), no FULL view requests, no shell commands.
