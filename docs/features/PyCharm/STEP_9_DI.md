# STEP 9 — DI: Runtime Adapter Selection

## Goal
Wire `PyCodeRepository` into `MaxVibesService` so the correct PSI adapter is chosen at runtime based on whether the project is a Python project .

## Location
```
maxvibes - plugin / src / main / kotlin / com / maxvibes / plugin / service / MaxVibesService.kt
```

## Current state (to change)
```kotlin
val codeRepository: CodeRepository by lazy { PsiCodeRepository(project) }
```

## After change
```kotlin
val codeRepository: CodeRepository by lazy {
    if (isPythonProject()) PyCodeRepository(project)
    else PsiCodeRepository(project)
}

private fun isPythonProject(): Boolean {
    val pythonPluginId = PluginId.getId("com.intellij.modules.python")
    return PluginManagerCore.isPluginInstalled(pythonPluginId) &&
            ModuleManager.getInstance(project).modules.any { module ->
                ModuleUtilCore.getModuleType(module).id.contains("PYTHON", ignoreCase = true)
            }
}
```

## Required imports
```kotlin
import com . intellij . ide . plugins . PluginManagerCore
        import com . intellij . openapi . extensions . PluginId
        import com . intellij . openapi . module . ModuleManager
        import com . intellij . openapi . roots . ModuleRootManager
        import com . intellij . openapi . module . ModuleUtilCore
        import com . maxvibes . adapter . psi . python . PyCodeRepository
```

## Alternative: simpler file -extension check
        If `ModuleUtilCore` causes issues (e.g.no modules loaded yet), fall back to checking open files :
```kotlin
private fun isPythonProject(): Boolean {
    val vfs = ProjectRootManager.getInstance(project).contentSourceRoots
    return vfs.any { root ->
        root.children.any { it.extension == "py" }
    }
}
```
This is less precise but always works .

## Guard against missing Python plugin at classload
Since `PyCodeRepository` is in `maxvibes-adapter-psi-python` which depends on `PythonCore`, classloading fails if the plugin is absent . Protect with:
```kotlin
private fun isPythonProject(): Boolean = try {
    Class.forName("com.jetbrains.python.psi.PyFile") != null &&
            hasPythonModules()
} catch (e: ClassNotFoundException) {
    false
}
```
This prevents `NoClassDefFoundError` on plain IntelliJ IDEA without Python plugin .

## plugin.xml — service registration guard
The `PyCodeRepository` class must only be referenced from a component that is loaded when `com.intellij.modules.python` is available . Use the optional extension file :

```xml
<!--maxvibes - python.xml(loaded only when Python plugin present)-- >
<extensions defaultExtensionNs ="com.intellij" >
<!--no extra services needed — MaxVibesService instantiates PyCodeRepository lazily-- >
</extensions >
```

No changes needed to `plugin.xml` beyond what was done in STEP_1.

## build.gradle.kts — module dependency
        In `maxvibes-plugin/build.gradle.kts`, add the Python adapter as a runtime dependency :
```kotlin
dependencies {
    implementation(project(":maxvibes-adapter-psi"))
    implementation(project(":maxvibes-adapter-psi-python"))  // ADD THIS
    // ... other deps
}
```

## Verification
After wiring :
1.Open IntelliJ IDEA with a Kotlin project → `isPythonProject()` returns false → `PsiCodeRepository` used .
2.Open PyCharm with a Python project → `isPythonProject()` returns true → `PyCodeRepository` used .
3.Open IntelliJ IDEA with a Python module → same as PyCharm case .

## Next step
        Proceed to * * STEP_10_SmokeTest.md * *.
