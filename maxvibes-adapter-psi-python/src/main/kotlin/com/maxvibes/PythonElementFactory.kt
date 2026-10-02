package com.maxvibes.adapter.psi.python

import com.intellij.openapi.project.Project
import com.jetbrains.python.psi.*
import com.intellij.psi.PsiFileFactory
import com.jetbrains.python.PythonLanguage
import com.maxvibes.domain.model.code.ElementKind

class PythonElementFactory(private val project: Project) {

    private val gen: PyElementGenerator
        get() = PyElementGenerator.getInstance(project)

    private val level: LanguageLevel
        get() = LanguageLevel.getLatest()

    fun createFunction(sourceText: String): PyFunction =
        gen.createFromText(level, PyFunction::class.java, sourceText)

    fun createClass(sourceText: String): PyClass =
        gen.createFromText(level, PyClass::class.java, sourceText)

    fun createAssignment(sourceText: String): PyAssignmentStatement =
        gen.createFromText(level, PyAssignmentStatement::class.java, sourceText)

    fun createStatement(sourceText: String): PyStatement =
        gen.createFromText(level, PyStatement::class.java, sourceText)

    fun createFile(sourceText: String): PyFile =
        PsiFileFactory.getInstance(project)
            .createFileFromText("dummy.py", PythonLanguage.getInstance(), sourceText) as PyFile
    fun createDeclaration(sourceText: String, kind: ElementKind): PyStatement {
        val file = createFile(com.intellij.openapi.util.text.StringUtil.convertLineSeparators(sourceText).trimIndent())
        val error =
            com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, com.intellij.psi.PsiErrorElement::class.java)
        require(error == null) { "Invalid Python declaration: ${error?.errorDescription}" }
        val statement = file.statements.singleOrNull()
            ?: throw IllegalArgumentException("CREATE_ELEMENT requires exactly one Python declaration")
        val matches = when (kind) {
            ElementKind.FUNCTION -> statement is PyFunction
            ElementKind.CLASS -> statement is PyClass
            ElementKind.PROPERTY -> statement is PyAssignmentStatement
            else -> throw IllegalArgumentException("Unsupported Python CREATE_ELEMENT kind: $kind")
        }
        require(matches) { "Python declaration does not match elementKind $kind" }
        return statement
    }
}
