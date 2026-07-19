# PyCharm Support — Implementation Plan

## Goal

Make MaxVibes work on PyCharm with full Python PSI code modification — same feature set as the current Kotlin / IDEA implementation (~85 - 90 % feature parity).

## Architecture Decision

**Single plugin artifact, two PSI adapters * * — both implement the `CodeRepository` port.`MaxVibesService` selects the adapter at runtime .

```
PsiCodeRepository(Kotlin PSI)  ─┐
├─► CodeRepository(port) ─► Use Cases ─► UI
PyCodeRepository(Python PSI)  ─┘
```

**Unchanged:** `maxvibes-domain`, `maxvibes-shared`, `maxvibes-application`, `maxvibes-adapter-llm`, chat, ClaudeCode, UI, settings, notifications.

## Audit Findings (from real source files)

| File | Finding | Action |
|------|-------- - |--------|
| `plugin.xml` | `<depends>org.jetbrains.kotlin</depends>` — **hard blocker * * for PyCharm | Make optional |
| `plugin.xml` | `<depends>com.intellij.modules.platform</depends>` — already present ✅ | Keep |
| `build.gradle.kts` | `org.jetbrains.intellij` 1.17.4(v1) | Add Python plugin dep to new module |
| `MaxVibesService` | `codeRepository by lazy { PsiCodeRepository(project) }` — hardcoded | Add runtime dispatch |

## Step List

| # | File(s) | What | Complexity |
|---|-------- - |------|------------|
| 1 | `plugin.xml` + 2 new XML files | Kotlin dep → optional; add Python optional dep | Small |
| 2 | `build.gradle.kts`(root + new module) + `settings.gradle.kts` | New `maxvibes-adapter-psi-python` submodule | Small |
| 3 | `PyPsiToDomainMapper.kt` | Map Python PSI → domain `CodeElement` | Small |
| 4 | `PyPsiNavigator.kt` | Navigate Python PSI by `ElementPath` segments | Medium |
| 5 | `PythonElementFactory.kt` | Create Python PSI elements via `PyElementGenerator` | Medium |
| 6 | `PyPsiModifier.kt` | Replace / create / delete Python PSI elements | Medium |
| 7 | `PyPsiCodeViewRenderer.kt` | Render code views at FULL / SIGNATURES / OUTLINE / ELEMENT | Small |
| 8 | `PyCodeRepository.kt` | Implement `CodeRepository`, orchestrate all Python adapters | Small |
| 9 | `MaxVibesService.kt` | Runtime adapter selection(Kotlin vs Python) | Small |
| 10 | Smoke test | Manual verification on PyCharm Community | — |

## New Module Structure

```
maxvibes - adapter - psi - python /
├── build.gradle.kts
└── src / main / kotlin / com / maxvibes / adapter / psi / python /
├── PyCodeRepository.kt
├── context /
│   └── PyIdeErrorsAdapter.kt
├── mapper /
│   └── PyPsiToDomainMapper.kt
├── operation /
│   ├── PyPsiNavigator.kt
│   └── PyPsiModifier.kt
├── factory /
│   └── PythonElementFactory.kt
└── renderer /
└── PyPsiCodeViewRenderer.kt
```

## Python PSI Key Classes

| Domain concept | Python PSI class | Package |
|---------------|---------------- - |--------|
| File | `PyFile` | `com.jetbrains.python.psi` |
| Class | `PyClass` | `com.jetbrains.python.psi` |
| Function / Method | `PyFunction` | `com.jetbrains.python.psi` |
| Variable / Attribute | `PyTargetExpression` | `com.jetbrains.python.psi` |
| Parameter | `PyNamedParameter` | `com.jetbrains.python.psi` |
| Imports | `PyImportStatement`, `PyFromImportStatement` | `com.jetbrains.python.psi` |
| Function body | `PyStatementList` | `com.jetbrains.python.psi` |
| Decorator | `PyDecorator` | `com.jetbrains.python.psi` |
| Element generator | `PyElementGenerator` | `com.jetbrains.python.psi` |

## Feature Parity

| Feature | Kotlin | Python | Notes |
|---------|--------|--------|------ - |
| Navigate to element | ✅ | ✅ | |
| Read code view | ✅ | ✅ | |
| Replace function | ✅ | ✅ | Preserve decorators !|
| Replace function body | ✅ | ✅ | `PyStatementList` |
| Add function to class | ✅ | ✅ | |
| Delete element | ✅ | ✅ | |
| IDE errors | ✅ | ✅ | PyCharm inspections |
| Docstrings | ✅ (buggy) | ✅ | Easier in Python |
| Import management | ✅ | ✅ | |
| Type hints | ✅ | ✅ | `PyAnnotation` |

## Known Python -specific Concerns

        1.* * Indentation is semantic** — `PyElementGenerator` handles this; don't manipulate raw text manually
2.* * Decorators * * — when replacing a `PyFunction`, preserve its `decoratorList` from the original element
3.* * `PyTargetExpression` * * — less rich than `KtProperty` (no
val /var, no explicit type by default)
4.* * `__init__` * * — same PSI type as any method (`PyFunction`), no special handling

## Non - goals(MVP)

-WebStorm / GoLand support (same approach applies later)
-Mixed projects (Python + Kotlin in one project) — AUTO detection picks one adapter
