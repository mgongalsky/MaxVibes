package com.maxvibes.adapter.psi.python

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyImportElement
import com.jetbrains.python.psi.PyImportStatementBase

/** Parses import requests and matches explicit file-level imports without resolving modules. */
internal object PythonImportLookup {
    data class Entry(val source: String?, val name: String, val alias: String?) {
        val qualifiedName: String
            get() = when {
                source == null -> name
                source.all { it == '.' } -> source + name
                else -> "$source.$name"
            }
    }

    data class Request(val entries: List<Entry>, val legacy: Boolean) {
        fun matches(expected: Entry, actual: Entry): Boolean =
            if (legacy) expected.qualifiedName == actual.qualifiedName else expected == actual
    }

    /** Accepts one explicit import statement or the legacy qualified-name format. */
    fun parse(file: PyFile, importPath: String): Request {
        val input = importPath.trim()
        val explicit = Regex("^(from|import)\\s").containsMatchIn(input)
        val source = if (explicit) {
            input
        } else {
            val identifier = "[\\p{L}_][\\p{L}\\p{N}_]*"
            require(Regex("\\.*$identifier(?:\\.$identifier)*").matches(input)) {
                "Expected a Python import statement or a qualified name: $importPath"
            }
            val relativeLevel = input.takeWhile { it == '.' }.length
            val tail = input.drop(relativeLevel)
            val separator = tail.lastIndexOf('.')
            when {
                separator >= 0 -> "from ${".".repeat(relativeLevel)}${
                    tail.substring(
                        0,
                        separator
                    )
                } import ${tail.substring(separator + 1)}"

                relativeLevel > 0 -> "from ${".".repeat(relativeLevel)} import $tail"
                else -> "import $tail"
            }
        }
        val parsed = PythonElementFactory(file.project).createFile(source + "\n")
        require(PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java) == null) {
            "Invalid Python import: $importPath"
        }
        val statement = parsed.statements.singleOrNull() as? PyImportStatementBase
            ?: throw IllegalArgumentException("Expected exactly one Python import statement")
        val entries = statement.importElements.map { entry(statement, it) }
        require(entries.isNotEmpty()) { "Wildcard imports are not supported; name the imported symbols explicitly" }
        return Request(entries.distinct(), legacy = !explicit)
    }

    fun entry(statement: PyImportStatementBase, element: PyImportElement): Entry {
        val source = if (statement is PyFromImportStatement) {
            ".".repeat(statement.relativeLevel) + statement.importSourceQName?.toString().orEmpty()
        } else null
        return Entry(source, requireNotNull(element.importedQName).toString(), element.asName)
    }

    fun contains(file: PyFile, importPath: String): Boolean {
        val request = parse(file, importPath)
        val actual = entries(file)
        return request.entries.all { expected -> actual.any { request.matches(expected, it) } }
    }

    fun containsAny(file: PyFile, importPath: String): Boolean {
        val request = parse(file, importPath)
        val actual = entries(file)
        return request.entries.any { expected -> actual.any { request.matches(expected, it) } }
    }

    private fun entries(file: PyFile): List<Entry> =
        file.statements.filterIsInstance<PyImportStatementBase>().flatMap { statement ->
            statement.importElements.map { entry(statement, it) }
        }
}
