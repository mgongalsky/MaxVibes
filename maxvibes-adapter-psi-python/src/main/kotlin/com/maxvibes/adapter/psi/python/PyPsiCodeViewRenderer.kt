package com.maxvibes.adapter.psi.python

import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyImportStatementBase
import com.jetbrains.python.psi.PyTargetExpression

/**
 * Renders Python PSI elements into textual representations of a given granularity.
 *
 * Python counterpart of the Kotlin `PsiCodeViewRenderer` from maxvibes-adapter-psi:
 * stateless and side-effect free — accepts ready-made PSI objects and produces strings.
 * **All methods must be invoked inside a read action** — the caller is responsible
 * for acquiring the appropriate lock.
 */
class PyPsiCodeViewRenderer {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Renders signatures of all top-level declarations in a file.
     *
     * Import statements are preserved verbatim. Function bodies are replaced with
     * `...` (valid Python stub syntax). Decorators and return annotations are kept —
     * both matter for LLM context. Module-level attributes are rendered as the first
     * line of their declaration statement.
     */
    fun renderSignatures(pyFile: PyFile): String = buildString {
        val imports = pyFile.statements.filterIsInstance<PyImportStatementBase>()
        if (imports.isNotEmpty()) {
            imports.forEach { appendLine(it.text) }
            appendLine()
        }

        val parts = mutableListOf<String>()
        pyFile.topLevelAttributes.forEach { parts.add(attributeSignature(it)) }
        pyFile.topLevelClasses.forEach { parts.add(renderClassSignatures(it)) }
        pyFile.topLevelFunctions.forEach { parts.add(functionSignature(it)) }
        append(parts.joinToString("\n\n"))
    }

    /**
     * Renders a compact outline of a whole file: classes with method signatures
     * only (no attributes) plus top-level function signatures.
     */
    fun renderOutline(pyFile: PyFile): String {
        val parts = mutableListOf<String>()
        pyFile.topLevelClasses.forEach { parts.add(renderOutline(it)) }
        pyFile.topLevelFunctions.forEach { parts.add(functionSignature(it)) }
        return parts.joinToString("\n\n")
    }

    /**
     * Renders a compact outline of a single class: header + method signatures.
     */
    fun renderOutline(pyClass: PyClass): String {
        val methods = pyClass.methods
        val header = classSignature(pyClass)
        return if (methods.isEmpty()) {
            "$header\n    ..."
        } else {
            header + "\n" + methods.joinToString("\n") { indent(functionSignature(it)) }
        }
    }

    /**
     * Returns the full source text of an element (`ELEMENT` granularity).
     */
    fun renderElement(element: PsiElement): String = element.text

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Class header + class attributes + method signatures. */
    private fun renderClassSignatures(pyClass: PyClass): String {
        val members = mutableListOf<String>()
        pyClass.classAttributes.forEach { members.add(indent(attributeSignature(it))) }
        pyClass.methods.forEach { members.add(indent(functionSignature(it))) }
        val header = classSignature(pyClass)
        return if (members.isEmpty()) "$header\n    ..." else header + "\n" + members.joinToString("\n")
    }

    /** `[decorators]` + `[async ]def name(params)[ -> Ret]: ...` */
    private fun functionSignature(fn: PyFunction): String {
        val decorators = fn.decoratorList?.decorators
            ?.joinToString("\n") { it.text }
            .orEmpty()
        val prefix = if (decorators.isNotEmpty()) "$decorators\n" else ""
        val asyncPrefix = if (fn.isAsync) "async " else ""
        val params = fn.parameterList.parameters.joinToString(", ") { it.text }
        val returnAnnotation = fn.annotation?.value?.text?.let { " -> $it" } ?: ""
        return "$prefix${asyncPrefix}def ${fn.name ?: "_"}($params)$returnAnnotation: ..."
    }

    /** `[decorators]` + `class Name(Bases):` */
    private fun classSignature(pyClass: PyClass): String {
        val decorators = pyClass.decoratorList?.decorators
            ?.joinToString("\n") { it.text }
            .orEmpty()
        val prefix = if (decorators.isNotEmpty()) "$decorators\n" else ""
        val bases = pyClass.superClassExpressions.joinToString(", ") { it.text }
        val baseClause = if (bases.isNotEmpty()) "($bases)" else ""
        return "${prefix}class ${pyClass.name ?: "_"}$baseClause:"
    }

    /**
     * First line of the attribute's declaration statement (assignment or annotated
     * declaration); `...` marks a trimmed multi-line initializer.
     */
    private fun attributeSignature(attr: PyTargetExpression): String {
        val text = (attr.parent ?: attr).text
        val firstLine = text.lineSequence().first().trimEnd()
        return if (text.contains('\n')) "$firstLine ..." else firstLine
    }

    private fun indent(text: String): String =
        text.lines().joinToString("\n") { "    $it" }
}
