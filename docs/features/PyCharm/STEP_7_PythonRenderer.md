# STEP 7 — PyPsiCodeViewRenderer

## Goal
Implement `PyPsiCodeViewRenderer` — renders Python PSI elements into the `CodeView` domain model that the application layer consumes.

## Location
```
maxvibes - adapter - psi - python / src / main / kotlin / com / maxvibes / adapter / psi / python /
        PyPsiCodeViewRenderer.kt
```

## Kotlin equivalent
        `PsiCodeViewRenderer.kt` in `maxvibes-adapter-psi` — same contract, Python - specific mapping .

## Implementation
```kotlin
package com.maxvibes.adapter.psi.python

import com . intellij . psi . PsiElement
        import com . jetbrains . python . psi . *
        import com . maxvibes . domain . model . code . CodeElement
        import com . maxvibes . domain . model . code . CodeGranularity
        import com . maxvibes . domain . model . code . CodeView

class PyPsiCodeViewRenderer {

    fun render(element: PsiElement, granularity: CodeGranularity): CodeView {
        return when (granularity) {
            CodeGranularity.FULL -> renderFull(element)
            CodeGranularity.SIGNATURES -> renderSignatures(element)
            CodeGranularity.OUTLINE -> renderOutline(element)
            CodeGranularity.ELEMENT -> renderElement(element)
        }
    }

    private fun renderFull(element: PsiElement): CodeView =
        CodeView(content = element.text, granularity = CodeGranularity.FULL)

    private fun renderElement(element: PsiElement): CodeView =
        CodeView(content = element.text, granularity = CodeGranularity.ELEMENT)

    private fun renderSignatures(element: PsiElement): CodeView {
        val lines = buildString {
            when (element) {
                is PyFile -> {
                    element.topLevelClasses.forEach { appendLine(classSignature(it)) }
                    element.topLevelFunctions.forEach { appendLine(functionSignature(it)) }
                }

                is PyClass -> {
                    appendLine(classSignature(element))
                    element.methods.forEach { appendLine("    " + functionSignature(it)) }
                    element.classAttributes.forEach { appendLine("    " + attributeSignature(it)) }
                }

                else -> appendLine(element.text)
            }
        }
        return CodeView(content = lines.trim(), granularity = CodeGranularity.SIGNATURES)
    }

    private fun renderOutline(element: PsiElement): CodeView {
        val lines = buildString {
            when (element) {
                is PyFile -> {
                    element.topLevelClasses.forEach { cls ->
                        appendLine(classSignature(cls))
                        cls.methods.forEach { appendLine("    " + functionSignature(it)) }
                    }
                    element.topLevelFunctions.forEach { appendLine(functionSignature(it)) }
                }

                is PyClass -> {
                    appendLine(classSignature(element))
                    element.methods.forEach { appendLine("    " + functionSignature(it)) }
                }

                else -> appendLine(element.text)
            }
        }
        return CodeView(content = lines.trim(), granularity = CodeGranularity.OUTLINE)
    }

    // ── signature helpers ────────────────────────────────────────────────────

    private fun classSignature(cls: PyClass): String {
        val bases = cls.superClassExpressions.joinToString(", ") { it.text }
        return if (bases.isNotEmpty()) "class ${cls.name}($bases):" else "class ${cls.name}:"
    }

    private fun functionSignature(fn: PyFunction): String {
        val params = fn.parameterList.parameters.joinToString(", ") { it.text }
        val returnAnnotation = fn.annotation?.text?.let { " -> $it" } ?: ""
        val decorators = fn.decoratorList?.decorators
            ?.joinToString("\n") { it.text } ?: ""
        val prefix = if (decorators.isNotEmpty()) "$decorators\n" else ""
        return "${prefix}def ${fn.name}($params)$returnAnnotation:"
    }

    private fun attributeSignature(attr: PyTargetExpression): String {
        val annotation = attr.annotation?.text?.let { ": $it" } ?: ""
        return "${attr.name}$annotation"
    }
}
```

## CodeView domain model
`CodeView` is already defined in `maxvibes-domain`.Check the actual fields in `CodeView.kt` before using — adapt the constructor call if it differs from the example above .

## Notes
-`SIGNATURES` omits function bodies but includes decorator names — important for LLM context .
-`fn.annotation` gives the return type annotation (`-> T`); `PyNamedParameter.annotation` gives parameter annotations .
-`OUTLINE` is a lighter version of `SIGNATURES` — classes +method names only, no attributes .

## Next step
        Proceed to * * STEP_8_PyCodeRepository.md * *.
