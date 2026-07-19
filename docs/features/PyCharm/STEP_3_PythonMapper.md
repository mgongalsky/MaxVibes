# STEP 3 — PyPsiToDomainMapper

## Purpose

Maps Python PSI elements to domain `CodeElement` objects . Mirrors `PsiToDomainMapper`.

## File

`maxvibes-adapter-psi-python/src/main/kotlin/com/maxvibes/adapter/psi/python/mapper/PyPsiToDomainMapper.kt`

## PSI → Domain Mappings

| Python PSI | Domain `ElementKind` | Notes |
|------------|-------------------- - |------ - |
| `PyFile` | `FILE` | |
| `PyClass` | `CLASS` | |
| `PyFunction`(top - level) | `FUNCTION` | |
| `PyFunction`(in class) | `METHOD` | check with `PsiTreeUtil.getParentOfType` |
| `PyTargetExpression`(module / class level) | `PROPERTY` | |
| `PyNamedParameter` | `PARAMETER` | |

## Signature

```kotlin
class PyPsiToDomainMapper {

    fun mapFile(pyFile: PyFile, basePath: String): CodeElement

    fun mapClass(pyClass: PyClass, parentPath: ElementPath): CodeElement

    fun mapFunction(pyFunction: PyFunction, parentPath: ElementPath): CodeElement

    fun mapTargetExpression(expr: PyTargetExpression, parentPath: ElementPath): CodeElement?

    fun mapElement(element: PsiElement, parentPath: ElementPath): CodeElement?

    fun inferKind(element: PsiElement): ElementKind?
}
```

## Implementation Notes

        -* * `PyFile.name` * *: strip `.py` suffix for display name; keep full path for `ElementPath.filePath`
-* * Method detection * * : `PsiTreeUtil.getParentOfType(pyFunction, PyClass::class.java) != null` → `METHOD`, else `FUNCTION`
-* * `PyTargetExpression` * *: use `name` for identifier, `text` for full assignment (`x: int = 0`)
-* * `inferKind` * *: must handle `PyFile`, `PyClass`, `PyFunction`, `PyTargetExpression`; return `null` for unknown types

## ElementPath Format for Python

Same segment format as Kotlin adapter :

| Path | Meaning |
|------|-------- - |
| `file:src/main.py` | file |
| `file:src/main.py/class[MyClass]` | class |
| `file:src/main.py/class[MyClass]/function[my_method]` | method |
| `file:src/main.py/function[standalone_func]` | top - level function |
| `file:src/main.py/property[MY_CONST]` | top - level variable |

## Reference

Before implementing, request `FULL` on `maxvibes-adapter-psi/src/main/kotlin/com/maxvibes/adapter/psi/mapper/PsiToDomainMapper.kt` to see exact `CodeElement` constructor usage — Python mapper must produce identical domain objects .
