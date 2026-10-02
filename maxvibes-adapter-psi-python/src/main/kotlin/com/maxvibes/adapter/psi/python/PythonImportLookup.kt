package com.maxvibes.adapter.psi.python

import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyImportStatementBase

/** Checks explicit file-level imports by FQN without resolving external modules. */
internal object PythonImportLookup {
    fun contains(file: PyFile, importPath: String): Boolean =
        file.statements.filterIsInstance<PyImportStatementBase>().any { statement ->
            statement.importElements.any { element ->
                val name = element.importedQName?.toString() ?: return@any false
                val qualifiedName = if (statement is PyFromImportStatement) {
                    val source = statement.importSourceQName?.toString().orEmpty()
                    val prefix = ".".repeat(statement.relativeLevel) + source
                    if (source.isEmpty()) "$prefix$name" else "$prefix.$name"
                } else {
                    name
                }
                qualifiedName == importPath
            }
        }
}
