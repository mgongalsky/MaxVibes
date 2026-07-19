# STEP 4 — PyPsiNavigator

## Goal
Implement `PyPsiNavigator` — navigates the Python PSI tree to locate files, classes, functions, and properties .

## Location
```
maxvibes - adapter - psi - python / src / main / kotlin / com / maxvibes / adapter / psi / python /
        PyPsiNavigator.kt
```

## Implementation
```kotlin
package com.maxvibes.adapter.psi.python

import com . intellij . openapi . project . Project
        import com . intellij . openapi . vfs . LocalFileSystem
        import com . intellij . psi . PsiElement
        import com . intellij . psi . PsiManager
        import com . jetbrains . python . psi . *

class PyPsiNavigator(private val project: Project) {

    fun findFile(path: String): PyFile? {
        val vFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return null
        return PsiManager.getInstance(project).findFile(vFile) as? PyFile
    }

    fun findElement(filePath: String, elementPath: String): PsiElement? {
        val pyFile = findFile(filePath) ?: return null
        val parts = elementPath.split(".")
        return when (parts.size) {
            1 -> findTopLevel(pyFile, parts[0])
            2 -> findMember(pyFile, parts[0], parts[1])
            else -> null
        }
    }

    fun getChildren(element: PsiElement): List<PsiElement> = when (element) {
        is PyFile -> element.topLevelClasses.toList() +
                element.topLevelFunctions.toList() +
                topLevelAttributes(element)

        is PyClass -> element.methods.toList() +
                element.classAttributes.toList() +
                element.instanceAttributes.toList()

        else -> element.children.toList()
    }

    private fun findTopLevel(file: PyFile, name: String): PsiElement? =
        file.topLevelClasses.firstOrNull { it.name == name }
            ?: file.topLevelFunctions.firstOrNull { it.name == name }
            ?: topLevelAttributes(file).firstOrNull { it.name == name }

    private fun findMember(file: PyFile, className: String, memberName: String): PsiElement? {
        val cls = file.topLevelClasses.firstOrNull { it.name == className } ?: return null
        return cls.methods.firstOrNull { it.name == memberName }
            ?: cls.classAttributes.firstOrNull { it.name == memberName }
            ?: cls.instanceAttributes.firstOrNull { it.name == memberName }
    }

    private fun topLevelAttributes(file: PyFile): List<PyTargetExpression> =
        file.statements
            .filterIsInstance<PyAssignmentStatement>()
            .flatMap { it.targets.toList() }
            .filterIsInstance<PyTargetExpression>()
}
```

## Key Python PSI APIs
| API | Returns | Notes |
|-----|-------- - |------ - |
| `PyFile.topLevelClasses` | `Array<PyClass>` | Direct class defs at module level |
| `PyFile.topLevelFunctions` | `Array<PyFunction>` | Direct function defs at module level |
| `PyFile.statements` | `Array<PyStatement>` | All top -level statements |
| `PyClass.methods` | `Array<PyFunction>` | Instance & class methods |
| `PyClass.classAttributes` | `Array<PyTargetExpression>` | Class - level assignments |
| `PyClass.instanceAttributes` | `Array<PyTargetExpression>` | `self.x =` assignments |

## elementPath convention
        -`"MyClass"` → top - level class
- `"my_function"` → top - level function
        -`"MyClass.my_method"` → method inside class
- `"MyClass.attribute"` → attribute inside class

## Next step
        Proceed to * * STEP_5_PythonElementFactory.md * *.
