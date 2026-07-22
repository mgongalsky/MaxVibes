package com.maxvibes.adapter.psi.python

import com.intellij.openapi.project.Project
import com.jetbrains.python.psi.*
import com.intellij.psi.PsiFileFactory
import com.jetbrains.python.PythonLanguage

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
}
