# STEP 10 — Smoke Test (PyCharm end-to-end + IDEA regression)

Rewritten 2026-07-05 to match the implemented C2/D code: `PyPsiCodeViewRenderer`,
`PyCodeRepository`, `addImport`/`removeImport` in `PyPsiModifier`, runtime dispatch in
`MaxVibesService` via `KotlinAdapterProvider`/`PythonAdapterProvider`.

## Prerequisites (hard blockers)

- [ ] Steps A/B/C finished (cleanup, python module `intellij { type = PC }`, file moves)
- [ ] C2 + D applied, whole project compiles. Must exist:
- `maxvibes-adapter-psi-python/src/main/kotlin/com/maxvibes/adapter/psi/python/PyPsiCodeViewRenderer.kt`
- `maxvibes-adapter-psi-python/src/main/kotlin/com/maxvibes/adapter/psi/python/PyCodeRepository.kt`
- `addImport`/`removeImport` in `PyPsiModifier`
- `maxvibes-plugin/.../service/KotlinAdapterProvider.kt` and `PythonAdapterProvider.kt`
- `MaxVibesService.createCodeRepository()` dispatch, no direct `PsiCodeRepository` import
- [ ] `StreamJsonProtocolTest.kt` moved to `maxvibes-plugin/src/test/kotlin`
(package `com.maxvibes.plugin.claudecode`). Note: JUnit/MockK are
`testImplementation`-scope in `maxvibes-plugin/build.gradle.kts`, so this file in the
MAIN sourceset breaks main compilation — the move is mandatory before any smoke run.
- [ ] PyCharm (Community is fine) installed locally
- [ ] LLM configured in MaxVibes settings (or drive the protocol via clipboard mode)

## Launching the plugin in PyCharm

Preferred — the dedicated Gradle task (already present in `maxvibes-plugin/build.gradle.kts`):

```
./gradlew :maxvibes-plugin:runIdePyCharm
```

- Resolves the IDE from the `PYCHARM_PATH` environment variable, otherwise falls back to
`C:/Program Files/JetBrains/PyCharm Community Edition 2023.1.5` on Windows.
Set `PYCHARM_PATH` if your install differs.
- Compatibility window: `sinceBuild=231`, `untilBuild=253.*` — any PyCharm 2023.1+ works.

Alternative — build a distribution and install into a live PyCharm:

```
./gradlew :maxvibes-plugin:buildPlugin
# PyCharm -> Settings -> Plugins -> gear icon -> Install Plugin from Disk -> build/distributions/*.zip
```

## Test fixture

Create `smoke/greeter.py` in the opened Python project (covers imports, module attribute,
class attribute, decorator, async, annotations):

```python
import os
from typing import Optional

DEFAULT_NAME = "World"


class Greeter:
    prefix: str = "Hello"

    def hello(self, name: str) -> str:
        return f"{self.prefix}, {name}"

    @staticmethod
    def shout(name: str) -> str:
        return name.upper()


async def fetch_greeting(url: str) -> Optional[str]:
    return None
```

## 1. Startup & dispatch

- [ ] MaxVibes tool window appears; Settings -> Tools -> MaxVibes opens
- [ ] No `ClassNotFoundException` / `NoClassDefFoundError` / `PluginException` in idea.log
      (Help -> Show Log) — in particular nothing referencing `PsiCodeRepository` or
      `org.jetbrains.kotlin` (regression risk #1, closed by step D)
- [ ] Trigger any code operation (attach greeter.py, send a message), then check
      `.maxvibes/logs/maxvibes.log` for the line `codeRepository dispatch` with
      `kotlin=false, python=true`. The line appears on FIRST use of `codeRepository`
      (lazy), not at IDE startup.

## 2. Read path — granularities on greeter.py

- [ ] FULL — exact file text delivered
- [ ] SIGNATURES — imports verbatim; `DEFAULT_NAME` and `prefix` rendered as one-line
      signatures; bodies replaced with `...`; `@staticmethod` preserved;
      `async def fetch_greeting(url: str) -> Optional[str]: ...` (async prefix + return
      annotation kept)
- [ ] OUTLINE without elementPath — whole-file outline: class headers + method signatures
      + top-level functions, no attributes
- [ ] OUTLINE with elementPath = `Greeter` — single-class outline
- [ ] ELEMENT with elementPath = `class[Greeter]/function[hello]` — full source of that
      method only

## 3. Write path — modifications

- [ ] REPLACE_ELEMENT on `class[Greeter]/function[hello]` (change the return string) —
      replaced in place, no duplicate, file still parses (no red code)
- [ ] Decorator preservation: REPLACE_ELEMENT on `shout` body — `@staticmethod` survives
- [ ] CREATE_ELEMENT — new method `goodbye(self, name)` inside `Greeter`, correct 4-space
      indentation, siblings untouched
- [ ] DELETE_ELEMENT — remove `goodbye`, class structure intact
- [ ] ADD_IMPORT dotted: `typing.List` -> merged into the existing from-import
      (`from typing import Optional, List`) or a new from-line
- [ ] ADD_IMPORT plain: `sys` -> `import sys`
- [ ] REMOVE_IMPORT partial: `typing.List` -> only the element removed, statement stays
      with `Optional`
- [ ] REMOVE_IMPORT sole element: `sys` -> whole `import sys` statement removed
- [ ] REMOVE_IMPORT plain unused: `os` -> whole statement removed, file stays valid
- [ ] REPLACE_FILE — full rewrite of greeter.py applies
- [ ] CREATE_FILE — e.g. `smoke/util/helpers.py`: the file lands EXACTLY at the requested
      path relative to the project root (directories created as needed). It must never
      appear at a different location — the Kotlin adapter's fallback heuristics are
      intentionally absent in `PyCodeRepository`
- [ ] DELETE_FILE — optional, remove helpers.py

## 4. IDE errors integration (platform path)

- [ ] Introduce a syntax error in an OPEN .py file, wait for highlighting, send a message —
      the error is attached (ideErrors) and the proposed fix applies

## 5. Edge cases

| Scenario | Expected |
|----------|----------|
| Empty .py file | SIGNATURES/OUTLINE return empty content, no crash |
| File with only imports | imports rendered, declaration list empty |
| Nested classes | v1: only top-level classes navigable |
| `__init__.py` | treated as a regular PyFile |
| Complex generics in annotations | raw annotation text rendered — OK |
| `async def` | `async ` prefix present in signatures |

## 6. IDEA / Kotlin regression

- [ ] `./gradlew :maxvibes-plugin:runIde`, open a Kotlin project
- [ ] maxvibes.log shows `codeRepository dispatch` with `kotlin=true` — Kotlin adapter
      selected, priority unchanged
- [ ] One quick REPLACE_ELEMENT on a Kotlin file works as before
- [ ] Gradle tests green: `:maxvibes-domain:test`, `:maxvibes-application:test`,
      `:maxvibes-shared:test`, `:maxvibes-adapter-llm:test`
- [ ] Plugin-module tests (incl. relocated `StreamJsonProtocolTest`) run via the IDEA
      test runner

## 7. Optional — no-language fallback

- [ ] In an IDE with neither Kotlin nor Python (e.g. WebStorm): code operations fail fast
      with the "No supported language plugin found" message from
      `UnsupportedLanguageCodeRepository`; no stacktrace crash

## Log locations

- IDE log: Help -> Show Log in Explorer
- Plugin log: `.maxvibes/logs/maxvibes.log` in the project root

## Recording results

Fill the checklist; for each failure report the exact modification JSON (if any), the
relevant `maxvibes.log` fragment and the idea.log stacktrace — one message per failing
group is enough.
