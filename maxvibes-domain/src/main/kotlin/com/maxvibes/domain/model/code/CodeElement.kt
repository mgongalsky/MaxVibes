// maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/code/CodeElement.kt
package com.maxvibes.domain.model.code

enum class ElementKind {
    FILE,
    CLASS, INTERFACE, OBJECT, ENUM,
    FUNCTION, PROPERTY,
    CONSTRUCTOR
}

sealed interface CodeElement {
    val path: ElementPath
    val name: String
    val kind: ElementKind
    val content: String
    val children: List<CodeElement>

    fun toCompactString(): String
}

data class CodeFile(
    override val path: ElementPath,
    override val name: String,
    override val content: String,
    val packageName: String?,
    val imports: List<String>,
    override val children: List<CodeElement>
) : CodeElement {
    override val kind = ElementKind.FILE

    override fun toCompactString(): String = buildString {
        appendLine("// File: $name")
        packageName?.let { appendLine("package $it") }
        if (imports.isNotEmpty()) {
            appendLine("// ${imports.size} imports")
        }
        appendLine()
        children.forEach { appendLine(it.toCompactString()) }
    }
}

data class CodeClass(
    override val path: ElementPath,
    override val name: String,
    override val content: String,
    override val kind: ElementKind,
    val modifiers: Set<String>,
    val superTypes: List<String>,
    override val children: List<CodeElement>
) : CodeElement {

    override fun toCompactString(): String = buildString {
        if (modifiers.isNotEmpty()) {
            append(modifiers.joinToString(" "))
            append(" ")
        }
        append(kind.name.lowercase())
        append(" $name")
        if (superTypes.isNotEmpty()) {
            append(" : ${superTypes.joinToString()}")
        }
        appendLine(" {")
        children.forEach {
            append("    ")
            appendLine(it.toCompactString())
        }
        appendLine("}")
    }
}

data class CodeFunction(
    override val path: ElementPath,
    override val name: String,
    override val content: String,
    val modifiers: Set<String>,
    val parameters: List<FunctionParameter>,
    val returnType: String?,
    val hasBody: Boolean
) : CodeElement {
    override val kind = ElementKind.FUNCTION
    override val children: List<CodeElement> = emptyList()

    override fun toCompactString(): String = buildString {
        if (modifiers.isNotEmpty()) {
            append(modifiers.joinToString(" "))
            append(" ")
        }
        append("fun $name(")
        append(parameters.joinToString { "${it.name}: ${it.type}" })
        append(")")
        returnType?.let { append(": $it") }
        if (hasBody) append(" { ... }")
    }
}

data class FunctionParameter(
    val name: String,
    val type: String,
    val defaultValue: String? = null
)

data class CodeProperty(
    override val path: ElementPath,
    override val name: String,
    override val content: String,
    val modifiers: Set<String>,
    val type: String?,
    val isVar: Boolean,
    val hasInitializer: Boolean
) : CodeElement {
    override val kind = ElementKind.PROPERTY
    override val children: List<CodeElement> = emptyList()

    override fun toCompactString(): String = buildString {
        if (modifiers.isNotEmpty()) {
            append(modifiers.joinToString(" "))
            append(" ")
        }
        append(if (isVar) "var" else "val")
        append(" $name")
        type?.let { append(": $it") }
        if (hasInitializer) append(" = ...")
    }
}