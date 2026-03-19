package com.maxvibes.adapter.psi.renderer

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Renders PSI elements into textual representations of a given granularity.
 *
 * Used to minimize token count when passing file context to an LLM — instead of
 * sending full file contents, callers can request only signatures, outline, or a
 * single element.
 *
 * Stateless and side-effect free: accepts ready-made PSI objects and produces strings.
 * **All methods must be invoked inside a read action** — the caller is responsible
 * for acquiring the appropriate lock (e.g. `ApplicationManager.getApplication().runReadAction`).
 */
class PsiCodeViewRenderer {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Renders signatures of all top-level declarations in a file.
     *
     * Package directive and import list are preserved verbatim.
     * Function bodies are replaced with `{ /* ... */ }` (block body) or `= ...`
     * (expression body). Properties are included in full (they are usually short).
     * Nested class members are also rendered as signatures recursively.
     *
     * @param ktFile Kotlin PSI file to render
     * @return string with signatures, ready to be inserted into an LLM prompt
     */
    fun renderSignatures(ktFile: KtFile): String = buildString {
        // Package line (e.g. "package com.example.foo")
        ktFile.packageDirective?.text?.takeIf { it.isNotBlank() }?.let {
            append(it)
            append("\n\n")
        }

        // Import block
        ktFile.importList?.text?.takeIf { it.isNotBlank() }?.let {
            append(it)
            append("\n\n")
        }

        // Top-level declarations joined by a blank line
        val parts = ktFile.declarations.map { decl ->
            when (decl) {
                is KtNamedFunction -> renderFunctionSignature(decl)
                is KtClass -> renderClassSignatures(decl)
                is KtProperty -> decl.text   // properties are short — keep as-is
                else -> decl.text
            }
        }
        append(parts.joinToString("\n\n"))
    }

    /**
     * Renders a compact outline of a class.
     *
     * The output includes:
     * - Class header (modifiers, keyword, name, type params, primary constructor, supertypes)
     * - A `// Properties` section listing each property as `[modifiers] val/var name: Type`
     * - A `// Functions` section listing method signatures with stub bodies
     *
     * More compact than [renderSignatures] for classes that have many or large member declarations.
     *
     * @param ktClass PSI class (data class, interface, object, etc.)
     * @return outline string ready to be inserted into a prompt
     */
    fun renderOutline(ktClass: KtClass): String = buildString {
        // Class header: everything up to the opening brace
        append(renderClassHeader(ktClass))
        append(" {")

        val body = ktClass.body
        if (body == null) {
            append("\n}")
            return@buildString
        }

        appendLine()

        // --- Properties section ---
        val properties = body.properties
        if (properties.isNotEmpty()) {
            appendLine()
            appendLine("    // Properties")
            for (prop in properties) {
                append("    ")
                appendLine(renderPropertyOutline(prop))
            }
        }

        // --- Functions section ---
        val functions = body.functions
        if (functions.isNotEmpty()) {
            appendLine()
            appendLine("    // Functions")
            for (fn in functions) {
                append("    ")
                appendLine(renderFunctionSignature(fn))
            }
        }

        append("}")
    }

    /**
     * Returns the full source text of a named declaration.
     *
     * Intended for `ELEMENT` granularity — the caller has already resolved
     * the element by its path, and we simply delegate to PSI which already
     * holds the exact original text including the body.
     *
     * @param element any named declaration (function, property, class, object, …)
     * @return element text exactly as it appears in the source file
     */
    fun renderElement(element: KtNamedDeclaration): String = element.text

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Renders a function signature with the body replaced by a stub placeholder.
     *
     * - Block body `{ ... }` → `{ /* ... */ }`
     * - Expression body `= expr` → `= ...`
     * - No body (abstract / interface) → text as-is
     *
     * KDoc and annotations are preserved because they precede the body in the
     * PSI text and are not touched by the range replacement.
     */
    private fun renderFunctionSignature(fn: KtNamedFunction): String {
        val text = fn.text

        // Block body: fun foo() { ... }  →  fun foo() { /* ... */ }
        val blockBody = fn.bodyBlockExpression
        if (blockBody != null) {
            val rangeStart = blockBody.textRangeInParent.startOffset
            return text.substring(0, rangeStart).trimEnd() + " { /* ... */ }"
        }

        // Expression body: fun foo() = expr  →  fun foo() = ...
        // bodyExpression is the part *after* the '=' sign
        val exprBody = fn.bodyExpression
        if (exprBody != null) {
            val rangeStart = exprBody.textRangeInParent.startOffset
            return text.substring(0, rangeStart).trimEnd() + " ..."
        }

        // Abstract / interface declaration — no body present
        return text
    }

    /**
     * Renders a property as `[modifiers] val/var name: Type` without the
     * initializer, getter, or setter bodies — suitable for outline display.
     *
     * If no explicit type reference exists (inferred type), the type part is omitted.
     */
    private fun renderPropertyOutline(prop: KtProperty): String = buildString {
        // Visibility and other modifiers (e.g. "private", "override")
        prop.modifierList?.text?.takeIf { it.isNotBlank() }?.let {
            append(it)
            append(" ")
        }

        append(if (prop.isVar) "var " else "val ")
        append(prop.name ?: "_")

        // Explicit type annotation — skip if the type is inferred
        prop.typeReference?.let { typeRef ->
            append(": ")
            append(typeRef.text)
        }
    }

    /**
     * Renders a class (or interface / object) with its members shown as signatures.
     *
     * The class header is kept intact; each member is rendered according to its kind:
     * - Functions → signature with stub body
     * - Properties → full text (short by nature)
     * - Nested classes → recursively rendered via this method
     * - Everything else → raw PSI text
     *
     * If the class has no body, only the header is emitted.
     */
    private fun renderClassSignatures(ktClass: KtClass): String = buildString {
        append(renderClassHeader(ktClass))

        val body = ktClass.body
        if (body == null) {
            return@buildString
        }

        appendLine(" {")

        val members = body.declarations
        if (members.isEmpty()) {
            appendLine("    // members...")
        } else {
            for (member in members) {
                when (member) {
                    is KtNamedFunction -> {
                        append("    ")
                        appendLine(renderFunctionSignature(member))
                    }

                    is KtProperty -> {
                        append("    ")
                        appendLine(member.text)
                    }

                    is KtClass -> {
                        // Recursively indent nested class
                        renderClassSignatures(member).lines().forEach { line ->
                            append("    ")
                            appendLine(line)
                        }
                    }

                    else -> {
                        append("    ")
                        appendLine(member.text)
                    }
                }
            }
        }

        append("}")
    }

    /**
     * Extracts the class header — everything up to (but not including) the
     * opening brace `{` of the class body.
     *
     * This naturally includes: visibility modifiers, `class`/`interface`/`object`
     * keyword, name, type parameters, primary constructor, and the supertype list.
     *
     * If the class has no body (e.g. a one-liner with no members), the full text
     * is returned trimmed.
     */
    private fun renderClassHeader(ktClass: KtClass): String {
        val text = ktClass.text
        val bodyStart = ktClass.body?.textRangeInParent?.startOffset
            ?: return text.trim()
        return text.substring(0, bodyStart).trim()
    }
}
