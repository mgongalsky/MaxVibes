# STEP 2 — Build Configuration : New Module

## Goal

Add `maxvibes-adapter-psi-python` as a Gradle submodule configured to compile against Python PSI APIs.

## settings.gradle.kts

Add `"maxvibes-adapter-psi-python"` to the `include()` list . Read current `settings.gradle.kts` first to confirm exact syntax.

## maxvibes - adapter - psi - python / build.gradle.kts

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij") version "1.17.4"
}

intellij {
    version.set("2024.1")          // match other modules
    type.set("IC")                  // IDEA Community — has PythonCore
    plugins.set(listOf("PythonCore"))  // Python Community plugin
    downloadSources.set(false)
    instrumentCode.set(false)
}

dependencies {
    implementation(project(":maxvibes-domain"))
    implementation(project(":maxvibes-shared"))
    implementation(project(":maxvibes-application"))
}

tasks.buildSearchableOptions { enabled = false }
tasks.patchPluginXml { enabled = false }
```

## maxvibes - plugin / build.gradle.kts

In the `dependencies {}` block add:

```kotlin
implementation(project(":maxvibes-adapter-psi-python"))
```

## Python Plugin IDs

| Distribution | Plugin ID |
|-------------|----------|
| PyCharm Community / IDEA +PythonCore | `PythonCore` |
| PyCharm Professional | `Pythonid` |
| IDEA Ultimate (bundled) | `python` |

`PythonCore` is sufficient for compilation — contains all `com.jetbrains.python.psi.*` classes .

## Verification

```bash
    ./ gradlew : maxvibes -adapter - psi - python:compileKotlin
```

If Python PSI classes resolve without errors, build config is correct .

## Note on intellij version

        The `version.set("2024.1")` in the new module must match the IntelliJ Platform version used by `maxvibes-plugin/build.gradle.kts`.Check that file before setting.
