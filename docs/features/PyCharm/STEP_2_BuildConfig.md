# STEP 2 — Build Configuration: maxvibes-adapter-psi-python

Rewritten 2026-07-05 after the phase-B compilation failure. Root cause: the applied
config had `type = PY` and **no `plugins.set(...)` at all**, so `com.jetbrains.python.*`
was absent from the compile classpath (~150 `Unresolved reference 'python'` errors).
The previous draft of this doc was also broken on its own: `2024.1` mismatched every
other module, and `IC` + bare `"PythonCore"` cannot resolve (see "Why these choices").

## Prerequisite: module hygiene (phase A)

The module directory must NOT contain its own `settings.gradle.kts`,
`gradle.properties`, `gradlew*`, or `gradle/` folder — these are IDE "New Module"
wizard artifacts. A nested `settings.gradle.kts` makes Gradle treat the directory as a
separate build and silently breaks the multi-module setup. Delete them by hand.

## settings.gradle.kts (root)

`include("maxvibes-adapter-psi-python")` — already present.

## maxvibes-adapter-psi-python/build.gradle.kts — target config

```kotlin
plugins {
kotlin("jvm")
id("org.jetbrains.intellij")
}

dependencies {
implementation(project(":maxvibes-domain"))
implementation(project(":maxvibes-application"))
implementation(project(":maxvibes-shared"))

testImplementation(kotlin("test"))
}

intellij {
version.set("2023.1.5")            // matches maxvibes-adapter-psi and maxvibes-plugin
type.set("PC")                     // PyCharm Community — PythonCore is bundled
plugins.set(listOf("PythonCore"))  // MANDATORY: puts com.jetbrains.python.* on the classpath
downloadSources.set(false)
instrumentCode.set(false)
}

tasks {
buildSearchableOptions { enabled = false }
buildPlugin { enabled = false }
runIde { enabled = false }
patchPluginXml { enabled = false }
}
```

## Why these choices

- **`plugins.set(listOf("PythonCore"))` is the load-bearing line.** Python support is a
separate plugin even inside PyCharm (`plugins/python-ce`); the IDE dependency alone
does NOT put `com.jetbrains.python.psi.*`, `PyElementGenerator`, or `AddImportHelper`
on the compile classpath. Omitting it reproduces the ~150-error cascade. This mirrors
the Kotlin adapter: `IC` + `plugins.set(listOf("org.jetbrains.kotlin"))`.
- **PC, not PY.** `Pythonid` (Professional) is an API superset; compiling against it
lets professional-only APIs slip in that are missing at runtime on the target
PyCharm CE. Everything this module uses is in the community core. Smaller download.
- **PC, not IC.** In IC, PythonCore is NOT bundled — it must come from the marketplace
with an explicit version (`PythonCore:<build>`); a bare ID fails to resolve. In PC it
is bundled, so the bare ID works and version drift is impossible.
- **2023.1.5** — in lockstep with `maxvibes-adapter-psi` and `maxvibes-plugin`. Upgrade
all three together.

## Plugin ID vs module ID — don't mix them up

| Context | Identifier |
|---------|-----------|
| Gradle `plugins.set(...)`, PyCharm Community (bundled) | `PythonCore` |
| Gradle `plugins.set(...)`, PyCharm Professional | `Pythonid` |
| `plugin.xml` `<depends optional="true" ...>` | `com.intellij.modules.python` |

The Gradle side names the *plugin*; `plugin.xml` names the *module*.
`com.intellij.modules.python` inside `plugins.set` will not resolve, and `PythonCore`
inside `<depends>` will never match.

## Troubleshooting sync/compile

- `Cannot find builtin plugin 'PythonCore'` → the error message lists the available
bundled IDs; use the one shown (known alternate: `python-ce`).
- PC `2023.1.5` fails to resolve → fall back to `version.set("2023.1.4")`.
- First sync downloads the PyCharm CE distribution (~500 MB) — one-time cost.

## maxvibes-plugin/build.gradle.kts

`implementation(project(":maxvibes-adapter-psi-python"))` — already present. The
IC-based plugin module does NOT need PythonCore on its own classpath: the only
references to Python-adapter classes live in `PythonAdapterProvider`, whose public
signature exposes only the `CodeRepository` port. If plugin-module compilation ever
fails on Python types, first check that isolation still holds; adding
`PythonCore:<231-compatible build>` to the plugin module's `plugins.set` is the last
resort, not the default.

## Verification

```
./gradlew :maxvibes-adapter-psi-python:compileKotlin
./gradlew :maxvibes-plugin:compileKotlin
```

Both green → proceed to the smoke test (STEP_10) via `runIdePyCharm`.
