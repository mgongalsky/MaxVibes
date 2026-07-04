package com.maxvibes.adapter.psi.python.mapper

import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.*
import com.maxvibes.domain.model.code.*

class PyPsiToDomainMapper {

    fun mapFile(pyFile: PyFile, basePath: String): CodeElement {
        val fullPath = pyFile.virtualFile?.path ?: pyFile.name
        val relativePath = if (basePath.isNotEmpty() && fullPath.startsWith(basePath)) {
            fullPath.removePrefix(basePath).trimStart('/', '\\')
        } else fullPath
        val path = ElementPath.file(relativePath)
        val children = mutableListOf<CodeElement>()
        pyFile.topLevelClasses.forEach { children.add(mapClass(it, path)) }
        pyFile.topLevelFunctions.forEach { children.add(mapFunction(it, path)) }
        return CodeFile(
            path = path,
            name = pyFile.name,
            content = pyFile.text,
            packageName = null,
            imports = pyFile.statements.filterIsInstance<PyImportStatementBase>().map { it.text },
            children = children
        )
    }

    fun mapClass(pyClass: PyClass, parentPath: ElementPath): CodeElement {
        val name = pyClass.name ?: "Anonymous"
        val path = parentPath.child("class", name)
        val children = mutableListOf<CodeElement>()
        pyClass.methods.forEach { children.add(mapFunction(it, path)) }
        (pyClass.classAttributes.toList() + pyClass.instanceAttributes.toList())
            .forEach { mapTargetExpression(it, path)?.let { el -> children.add(el) } }
        return CodeClass(
            path = path,
            name = name,
            content = pyClass.text,
            kind = ElementKind.CLASS,
            modifiers = emptySet(),
            superTypes = pyClass.superClassExpressions.map { it.text },
            children = children
        )
    }

    fun mapFunction(pyFunction: PyFunction, parentPath: ElementPath): CodeElement {
        val name = pyFunction.name ?: "anonymous"
        val path = parentPath.child("function", name)
        return CodeFunction(
            path = path,
            name = name,
            content = pyFunction.text,
            modifiers = emptySet(),
            parameters = pyFunction.parameterList.parameters.map { param ->
                FunctionParameter(
                    name = param.name ?: "_",
                    type = (param as? PyNamedParameter)?.annotation?.value?.text ?: "Any",
                    defaultValue = (param as? PyNamedParameter)?.defaultValue?.text
                )
            },
            returnType = pyFunction.annotation?.value?.text,
            hasBody = pyFunction.statementList.statements.isNotEmpty()
        )
    }

    fun mapTargetExpression(expr: PyTargetExpression, parentPath: ElementPath): CodeElement? {
        val name = expr.name ?: return null
        val path = parentPath.child("property", name)
        return CodeProperty(
            path = path,
            name = name,
            content = expr.text,
            modifiers = emptySet(),
            type = expr.annotation?.value?.text,
            isVar = true,
            hasInitializer = true
        )
    }

    fun mapElement(element: PsiElement, parentPath: ElementPath): CodeElement? = when (element) {
        is PyFile -> mapFile(element, "")
        is PyClass -> mapClass(element, parentPath)
        is PyFunction -> mapFunction(element, parentPath)
        is PyTargetExpression -> mapTargetExpression(element, parentPath)
        else -> null
    }

    fun inferKind(element: PsiElement): ElementKind? = when (element) {
        is PyFile -> ElementKind.FILE
        is PyClass -> ElementKind.CLASS
        is PyFunction -> ElementKind.FUNCTION
        is PyTargetExpression -> ElementKind.PROPERTY
        else -> null
    }
}
