# STEP 5 — PythonElementFactory

## Goal
Implement `PythonElementFactory` — creates new PSI elements from source text using `PyElementGenerator` .

## Location
```
maxvibes - adapter - psi - python / src / main / kotlin / com / maxvibes / adapter / psi / python /
        PythonElementFactory.kt
```

## PyElementGenerator overview
        `PyElementGenerator` is the Python PSI factory, analogous to `KotlinElementFactory`.Key method :
```kotlin
fun <T : PsiElement> createFromText(
    langLevel: LanguageLevel,
    aClass: Class<T>,
    text: String
): T
```

## Implementation
```kotlin
package com.maxvibes.adapter.psi.python

import com . intellij . openapi . project . Project
        import com . jetbrains . python . psi . *

class PythonElementFactory(private val project: Project) {

    private val gen: PyElementGenerator
        get() = PyElementGenerator.getInstance(project)

    private val level: LanguageLevel
        get() = LanguageLevel.getLatest()

    fun createFunction(sourceText: String): PyFunction =
        gen.createFromText(level, PyFunction::class.java, sourceText)

    fun createClass(sourceText: String): PyClass =
        gen.createFromText(level, PyClass::class.java, sourceText)

    fun createAssignment(sourceText: String): PyAssignmentStatement =
        gen.createFromText(level, PyAssignmentStatement::class.java, sourceText)

    fun createStatement(sourceText: String): PyStatement =
        gen.createFromText(level, PyStatement::class.java, sourceText)

    fun createFile(sourceText: String): PyFile =
        gen.createFromText(level, PyFile::class.java, sourceText)
}
```

## Decorator preservation
        When replacing a function, the caller (PyPsiModifier) must copy decorators before replacement :

```kotlin
// Copy decorator list from old function if new source has none
val oldDecorators = oldFn.decoratorList
if (oldDecorators != null && newFn.decoratorList == null) {
    val cloned = oldDecorators.copy() as PsiElement
    newFn.addBefore(cloned, newFn.firstChild)
}
```

This logic lives in `PyPsiModifier`(STEP 6) — factory only creates elements .

## Notes
-`LanguageLevel.getLatest()` resolves to Python 3.x.
-`createFromText` throws `IncorrectOperationException` on malformed source — catch and wrap as `CodeRepositoryError.WriteError` .
-All calls must be inside a `WriteCommandAction` — handled by `PyPsiModifier`.

## Next step
        Proceed to * * STEP_6_PythonModifier.md * *.
