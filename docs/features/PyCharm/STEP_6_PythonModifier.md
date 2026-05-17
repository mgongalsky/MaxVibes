# STEP 6 — PyPsiModifier

## Goal
Implement `PyPsiModifier` — applies PSI mutations (replace, insert, delete) inside `WriteCommandAction`, preserving decorators .

## Location
```
maxvibes - adapter - psi - python / src / main / kotlin / com / maxvibes / adapter / psi / python /
        PyPsiModifier.kt
```

## Implementation
```kotlin
package com.maxvibes.adapter.psi.python

import com . intellij . openapi . command . WriteCommandAction
        import com . intellij . openapi . project . Project
        import com . intellij . psi . PsiElement
        import com . jetbrains . python . psi . *
        import com . maxvibes . domain . model . modification . Modification
        import com . maxvibes . domain . model . modification . ModificationType

class PyPsiModifier(
    private val project: Project,
    private val navigator: PyPsiNavigator,
    private val factory: PythonElementFactory
) {

    fun apply(mod: Modification): Result<Unit> = runCatching {
        WriteCommandAction.runWriteCommandAction(project) {
            applyInternal(mod)
        }
    }

    private fun applyInternal(mod: Modification) {
        val pyFile = navigator.findFile(mod.filePath)
            ?: error("File not found: ${mod.filePath}")

        when (mod.type) {
            ModificationType.REPLACE_ELEMENT -> replaceElement(pyFile, mod)
            ModificationType.INSERT_BEFORE -> insertRelative(pyFile, mod, before = true)
            ModificationType.INSERT_AFTER -> insertRelative(pyFile, mod, before = false)
            ModificationType.DELETE_ELEMENT -> deleteElement(pyFile, mod)
            ModificationType.REPLACE_FILE -> replaceFile(pyFile, mod)
        }
    }

    private fun replaceElement(pyFile: PyFile, mod: Modification) {
        val target = navigator.findElement(mod.filePath, mod.elementPath)
            ?: error("Element not found: ${mod.elementPath}")
        val newElement = createMatchingElement(target, mod.newContent)
        if (target is PyFunction && newElement is PyFunction) {
            copyDecorators(target, newElement)
        }
        target.replace(newElement)
    }

    private fun insertRelative(pyFile: PyFile, mod: Modification, before: Boolean) {
        val anchor = navigator.findElement(mod.filePath, mod.elementPath)
            ?: error("Anchor not found: ${mod.elementPath}")
        val newElement = createMatchingElement(anchor, mod.newContent)
        if (before) anchor.parent.addBefore(newElement, anchor)
        else anchor.parent.addAfter(newElement, anchor)
    }

    private fun deleteElement(pyFile: PyFile, mod: Modification) {
        val target = navigator.findElement(mod.filePath, mod.elementPath)
            ?: error("Element not found: ${mod.elementPath}")
        target.delete()
    }

    private fun replaceFile(pyFile: PyFile, mod: Modification) {
        val newFile = factory.createFile(mod.newContent)
        pyFile.children.forEach { it.delete() }
        newFile.children.forEach { pyFile.add(it) }
    }

    private fun createMatchingElement(target: PsiElement, source: String): PsiElement =
        when (target) {
            is PyFunction -> factory.createFunction(source)
            is PyClass -> factory.createClass(source)
            is PyAssignmentStatement -> factory.createAssignment(source)
            else -> factory.createStatement(source)
        }

    private fun copyDecorators(source: PyFunction, dest: PyFunction) {
        val srcDecorators = source.decoratorList ?: return
        if (dest.decoratorList != null) return  // new source already has decorators
        val cloned = srcDecorators.copy() as PsiElement
        dest.addBefore(cloned, dest.firstChild)
    }
}
```

## WriteCommandAction contract
        -ALL PSI mutations must be inside `WriteCommandAction.runWriteCommandAction`.
-Nested calls are safe — IntelliJ coalesces them .
-Never mutate PSI outside a write action — throws `IncorrectOperationException` .

## Error propagation
        `runCatching` wraps internal errors . `PyCodeRepository` (STEP 8) maps `Result.failure` → `CodeRepositoryError.WriteError` .

## Next step
        Proceed to * * STEP_7_PythonRenderer.md * *.
