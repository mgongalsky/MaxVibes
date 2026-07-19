# STEP 8 — PyCodeRepository

## Goal
Implement `PyCodeRepository` — the top - level Python PSI adapter that implements the `CodeRepository` port.This is the single entry point wired into `MaxVibesService` .

## Location
```
maxvibes - adapter - psi - python / src / main / kotlin / com / maxvibes / adapter / psi / python /
        PyCodeRepository.kt
```

## Port to implement
`CodeRepository` from `maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/CodeRepository.kt`

Before writing this class, request `SIGNATURES` of `CodeRepository.kt` to confirm the exact method signatures :
```json
{ "path": "maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/CodeRepository.kt", "granularity": "FULL" }
```

## Implementation(based on known signatures)
```kotlin
package com.maxvibes.adapter.psi.python

import com . intellij . openapi . project . Project
        import com . maxvibes . application . port . output . CodeRepository
        import com . maxvibes . application . port . output . CodeRepositoryError
        import com . maxvibes . domain . model . code . *
        import com . maxvibes . domain . model . modification . Modification

class PyCodeRepository(project: Project) : CodeRepository {

    private val navigator = PyPsiNavigator(project)
    private val factory = PythonElementFactory(project)
    private val mapper = PyPsiToDomainMapper()
    private val modifier = PyPsiModifier(project, navigator, factory)
    private val renderer = PyPsiCodeViewRenderer()

    override fun getFileContent(path: String): Result<String> = runCatching {
        navigator.findFile(path)?.text
            ?: throw CodeRepositoryError.NotFound("File not found: $path")
    }

    override fun getElement(filePath: String, elementPath: String): Result<CodeElement> = runCatching {
        val psiElement = navigator.findElement(filePath, elementPath)
            ?: throw CodeRepositoryError.NotFound("Element not found: $elementPath in $filePath")
        mapper.mapToCodeElement(psiElement)
    }

    override fun findElements(
        filePath: String,
        granularity: CodeGranularity
    ): Result<List<CodeElement>> = runCatching {
        val file = navigator.findFile(filePath)
            ?: throw CodeRepositoryError.NotFound("File not found: $filePath")
        navigator.getChildren(file).map { mapper.mapToCodeElement(it) }
    }

    override fun applyModification(modification: Modification): Result<Unit> =
        modifier.apply(modification)
            .mapError { CodeRepositoryError.WriteError(it.message ?: "PSI write failed") }

    override fun applyModifications(modifications: List<Modification>): Result<Unit> = runCatching {
        modifications.forEach { mod ->
            modifier.apply(mod).getOrThrow()
        }
    }

    override fun exists(path: String): Boolean =
        navigator.findFile(path) != null

    override fun validateSyntax(filePath: String): Result<Unit> = runCatching {
        val file = navigator.findFile(filePath)
            ?: throw CodeRepositoryError.NotFound("File not found: $filePath")
        // PyCharm provides syntax validation via PsiErrorElement children
        val errors = file.children.filterIsInstance<com.intellij.psi.PsiErrorElement>()
        if (errors.isNotEmpty()) {
            throw CodeRepositoryError.ValidationError(errors.first().errorDescription)
        }
    }

    override fun getCodeView(
        filePath: String,
        elementPath: String?,
        granularity: CodeGranularity
    ): Result<CodeView> = runCatching {
        val element = if (elementPath != null) {
            navigator.findElement(filePath, elementPath)
                ?: throw CodeRepositoryError.NotFound("Element not found: $elementPath")
        } else {
            navigator.findFile(filePath)
                ?: throw CodeRepositoryError.NotFound("File not found: $filePath")
        }
        renderer.render(element, granularity)
    }
}
```

## Result.mapError helper
        If `Result<T>.mapError` is not available in the project, use:
```kotlin
private fun <T> Result<T>.mapError(transform: (Throwable) -> Throwable): Result<T> =
    this.recoverCatching { throw transform(it) }
```

## Wiring into MaxVibesService(preview for STEP 9)
In `MaxVibesService.kt`, the current code is :
```kotlin
val codeRepository: CodeRepository by lazy { PsiCodeRepository(project) }
```
After this step it will become:
```kotlin
val codeRepository: CodeRepository by lazy {
    if (isPythonProject(project)) PyCodeRepository(project)
    else PsiCodeRepository(project)
}
```
The `isPythonProject` check uses `ModuleUtilCore` or `PluginManagerCore` — covered in * * STEP_9_DI.md * *.

## Next step
        Proceed to * * STEP_9_DI.md * *.
