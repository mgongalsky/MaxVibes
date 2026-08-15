# Addendum: fourth REPLACE_ELEMENT file - truncation incident

        Parent issue : `psi-replace-nested-element-truncates-file.md` .

## Incident

A single element replacement targeted an ordinary private member function in the plugin UI composition root :

```text
file: maxvibes - plugin / src / main / kotlin / com / maxvibes / plugin / ui / ChatMessageControllerComposition.kt
elementPath: class[ChatMessageControllerComposition]/function[runClaudeCodeBg]
operation: REPLACE_ELEMENT
```

Expected: replace only `runClaudeCodeBg` while preserving the package, imports, enclosing class, properties, coordinators and public facade methods.Actual: the complete file became exactly the submitted function :

```kotlin
private fun runClaudeCodeBg(...) = interactionExecutionCoordinator.runClaudeCode(...)
```

The compiler then reported `ChatMessageControllerComposition` as unresolved from its consumer and produced a cascade of unresolved references inside the orphaned top - level function .

## Why this case matters

This reproduces the same data - loss bug in a different Gradle module(`maxvibes-plugin`) and with a plain non -suspend member function.Together with the three application - module incidents, it rules out these narrower explanations :

-application - module - specific PSI configuration;
-only nested objects / interfaces being affected;
-only `const`, nested data classes or suspend functions being affected;
-malformed replacement source;
-multiple modifications or stale offsets in one batch .

The common factor remains : `REPLACE_ELEMENT` receives a nested element path but replaces the `KtFile` with the new leaf declaration .

## Additional regression case

Add a real Kotlin PSI integration test that replaces a private function in a large class with many constructor dependencies and sibling properties . Assert byte - for -byte preservation of package/imports and all non -target declarations .

The test must also assert that the resolved replacement receiver is the leaf `KtNamedFunction`, never `KtFile`, and that the operation is rolled back if the enclosing class cannot be re - resolved afterward .
