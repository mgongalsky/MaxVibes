# STEP 5 — Пребилд-скиллы + installer

Цель: 8 стартовых скиллов ресурсами плагина + кнопка «Install Starter Skills» в SkillManagerDialog, копирующая недостающие в project .claude/skills/. После установки это обычные файлы — правь как хочешь; distinctBy в репозитории уже даёт затенение (project > global).

## 5.1 Ресурсы
`maxvibes-plugin/src/main/resources/skills/<id>/SKILL.md`, ids: feathers-characterize, feathers-seam, feathers-sprout, feathers-extract-override, explain-element, find-smells, write-kdoc, write-unittest. Содержимое — ниже, копировать дословно (CREATE_FILE по одному на файл).

### skills/feathers-characterize/SKILL.md
```
---
name: feathers-characterize
description: Characterization tests (Feathers) that pin current behavior before changing it
applies-to: function, class
attach-element: true
editor-template: |
Write characterization tests for {{elementPath}}.
Before writing, request USAGES for it via requestedViews to see real call patterns and edge cases worth pinning.
---
Write characterization tests in the Feathers sense: capture what the code ACTUALLY does now, not what it should do.
Rules: do not modify production code; put tests in src/test/kotlin of the owning module; JUnit5 + MockK; coEvery for suspend functions; runBlocking (never runTest).
Cover the golden path plus the edge cases visible at real call sites. Name each test after the observed behavior it pins.
```

### skills/feathers-seam/SKILL.md
```
---
name: feathers-seam
description: Seam analysis (Feathers) — find where to break dependencies, no code changes
applies-to: function, class
attach-element: true
editor-template: |
Analyze {{elementPath}} for seams (Feathers). Analysis only — no modifications, no commands.
Also request USAGES and CALLERS for it to map the dependency pressure.
---
List every hard dependency of the element (I/O, static calls, constructors, time, process, singletons).
For each, propose a breaking technique — Extract Interface, Parameterize Constructor, or Extract and Override — with its trade-off.
Finish with a recommendation: the single cheapest seam to introduce first. Keep modifications and commands empty.
```

### skills/feathers-sprout/SKILL.md
```
---
name: feathers-sprout
description: Sprout Method (Feathers) — add new logic as a separate tested function
applies-to: function
attach-element: true
editor-template: |
Use Sprout Method (Feathers) on {{elementName}} ({{elementPath}}).
New logic to add: <describe here>
---
Implement the new logic as a NEW separate function with its own unit test; change the original function minimally — ideally a single call line.
Prefer CREATE_ELEMENT for the sprout function and one REPLACE_ELEMENT for the host. Do not restructure the host beyond the insertion.
```

### skills/feathers-extract-override/SKILL.md
```
---
name: feathers-extract-override
description: Extract and Override (Feathers) — make a function testable by extracting its hard dependency
applies-to: function
attach-element: true
editor-template: |
Prepare {{elementPath}} for testing via Extract and Override (Feathers).
---
Identify the hardest dependency in the body (I/O, time, static, process, UI). Extract it into a protected open method with a clear name.
Show a testing subclass that overrides the extracted method, and one test using that subclass. Behavior of the production path must stay identical.
```

### skills/explain-element/SKILL.md
```
---
name: explain-element
description: Explain what an element does, its invariants and non-obvious parts
applies-to: any
attach-element: true
editor-template: |
Explain {{elementPath}}: purpose, how it works, non-obvious parts and invariants. No modifications.
---
Explain responsibilities, collaborators, and threading/EDT expectations where visible. Call out invariants and surprising behavior.
If callers matter for understanding, request CALLERS one level via requestedViews. Analysis only — keep modifications and commands empty.
```

### skills/find-smells/SKILL.md
```
---
name: find-smells
description: Review an element for smells, risks and hidden side effects
applies-to: any
attach-element: true
editor-template: |
Review {{elementPath}} for smells and risks. Analysis only.
---
Look for SRP violations, hidden side effects, error-handling gaps, and threading hazards. When the element is a class member, request OUTLINE of the containing class for structure.
Prioritize findings (high/medium/low) and for each suggest the smallest safe refactoring. Keep modifications and commands empty.
```

### skills/write-kdoc/SKILL.md
```
---
name: write-kdoc
description: Write KDoc for an element in the project's existing doc style
applies-to: function, property, class
attach-element: true
editor-template: |
Write KDoc for {{elementPath}} matching the project's existing KDoc style.
---
Return exactly one REPLACE_ELEMENT: the element UNCHANGED plus KDoc on top. Document parameters, return value, and threading notes when relevant.
Match the tone and depth of the project's existing documentation. No behavioral changes.
```

### skills/write-unittest/SKILL.md
```
---
name: write-unittest
description: Write unit tests for an element, grounded in its real call sites
applies-to: function, class
attach-element: true
editor-template: |
Write unit tests for {{elementPath}}. Request USAGES first to ground the cases in real call sites.
---
JUnit5 + MockK; coEvery + runBlocking for suspend functions (never runTest). Tests go to src/test/kotlin of the owning module.
Do not touch production code. Cover boundaries and failure paths, not just the golden path.
```

## 5.2 Installer в SkillManagerDialog
Перед правкой запросить SkillManagerDialog.kt FULL (тела createActions/createCenterPanel/createSkill нужны, чтобы встроить кнопку в существующий паттерн). Добавить кнопку «Install Starter Skills» рядом с существующими и метод:

```kotlin
private val starterSkills = listOf(
"feathers-characterize", "feathers-seam", "feathers-sprout", "feathers-extract-override",
"explain-element", "find-smells", "write-kdoc", "write-unittest"
)

private fun installStarterSkills() {
val root = File(project.basePath, ".claude/skills")
var installed = 0
var skipped = 0
starterSkills.forEach { id ->
val dir = File(root, id)
if (File(dir, "SKILL.md").exists()) { skipped++; return@forEach }
val res = javaClass.getResourceAsStream("/skills/" + id + "/SKILL.md") ?: return@forEach
dir.mkdirs()
res.use { input -> File(dir, "SKILL.md").writeBytes(input.readBytes()) }
installed++
}
reload()
Messages.showInfoMessage(project, "Installed: " + installed + ", already present: " + skipped, "Starter Skills")
}
```
Существующие директории НЕ перезаписываются — отредактированное пользователем затирается только вручную.

## Проверка шага
Rebuild → Manage Skills → Install Starter Skills → 8 директорий в .claude/skills/; повторный клик → installed 0, present 8; скиллы видны в списке менеджера и в дропдауне.
