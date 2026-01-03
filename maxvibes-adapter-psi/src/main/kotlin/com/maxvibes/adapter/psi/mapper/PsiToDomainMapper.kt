package com.maxvibes.adapter.psi.mapper

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.maxvibes.domain.model.code.*
import org.jetbrains.kotlin.psi.*

/**
 * Маппер PSI -> Domain модели
 */
class PsiToDomainMapper {

    fun mapFile(psiFile: PsiFile, basePath: String): CodeElement {
        return when (psiFile) {
            is KtFile -> mapKtFile(psiFile, basePath)
            else -> throw IllegalArgumentException("Unsupported file type: ${psiFile.javaClass}")
        }
    }

    private fun mapKtFile(ktFile: KtFile, basePath: String): CodeFile {
        val filePath = ktFile.virtualFile?.path?.removePrefix(basePath)?.removePrefix("/")
            ?: ktFile.name
        val path = ElementPath.file(filePath)

        val children = ktFile.declarations.mapNotNull { decl ->
            mapDeclaration(decl, path)
        }

        return CodeFile(
            path = path,
            name = ktFile.name,
            content = ktFile.text,
            packageName = ktFile.packageFqName.asString().takeIf { it.isNotEmpty() },
            imports = ktFile.importDirectives.mapNotNull { it.importPath?.pathStr },
            children = children
        )
    }

    fun mapDeclaration(element: PsiElement, parentPath: ElementPath): CodeElement? {
        return when (element) {
            is KtClass -> mapClass(element, parentPath)
            is KtNamedFunction -> mapFunction(element, parentPath)
            is KtProperty -> mapProperty(element, parentPath)
            is KtObjectDeclaration -> mapObject(element, parentPath)
            else -> null
        }
    }

    private fun mapClass(ktClass: KtClass, parentPath: ElementPath): CodeClass {
        val path = parentPath.child("class", ktClass.name ?: "Anonymous")

        val children = ktClass.declarations.mapNotNull { decl ->
            mapDeclaration(decl, path)
        }

        val kind = when {
            ktClass.isInterface() -> ElementKind.INTERFACE
            ktClass.isEnum() -> ElementKind.ENUM
            else -> ElementKind.CLASS
        }

        return CodeClass(
            path = path,
            name = ktClass.name ?: "Anonymous",
            content = ktClass.text,
            kind = kind,
            modifiers = extractModifiers(ktClass),
            superTypes = ktClass.superTypeListEntries.map { it.text },
            children = children
        )
    }

    private fun mapObject(ktObject: KtObjectDeclaration, parentPath: ElementPath): CodeClass {
        val name = ktObject.name ?: "Companion"
        val path = parentPath.child("object", name)

        val children = ktObject.declarations.mapNotNull { decl ->
            mapDeclaration(decl, path)
        }

        return CodeClass(
            path = path,
            name = name,
            content = ktObject.text,
            kind = ElementKind.OBJECT,
            modifiers = extractModifiers(ktObject),
            superTypes = ktObject.superTypeListEntries.map { it.text },
            children = children
        )
    }

    private fun mapFunction(ktFunction: KtNamedFunction, parentPath: ElementPath): CodeFunction {
        val name = ktFunction.name ?: "anonymous"
        val path = parentPath.child("function", name)

        return CodeFunction(
            path = path,
            name = name,
            content = ktFunction.text,
            modifiers = extractModifiers(ktFunction),
            parameters = ktFunction.valueParameters.map { param ->
                FunctionParameter(
                    name = param.name ?: "_",
                    type = param.typeReference?.text ?: "Any",
                    defaultValue = param.defaultValue?.text
                )
            },
            returnType = ktFunction.typeReference?.text,
            hasBody = ktFunction.hasBody()
        )
    }

    private fun mapProperty(ktProperty: KtProperty, parentPath: ElementPath): CodeProperty {
        val name = ktProperty.name ?: "anonymous"
        val path = parentPath.child("property", name)

        return CodeProperty(
            path = path,
            name = name,
            content = ktProperty.text,
            modifiers = extractModifiers(ktProperty),
            type = ktProperty.typeReference?.text,
            isVar = ktProperty.isVar,
            hasInitializer = ktProperty.hasInitializer()
        )
    }

    private fun extractModifiers(element: KtModifierListOwner): Set<String> {
        val modifiers = mutableSetOf<String>()
        element.modifierList?.let { modifierList ->
            if (modifierList.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PUBLIC_KEYWORD)) modifiers.add("public")
            if (modifierList.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) modifiers.add("private")
            if (modifierList.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD)) modifiers.add("protected")
            if (modifierList.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD)) modifiers.add("internal")
            if (modifierList.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OPEN_KEYWORD)) modifiers.add("open")
            if (modifierList.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)) modifiers.add("abstract")
            if (modifierList.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD)) modifiers.add("override")
            if (modifierList.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.DATA_KEYWORD)) modifiers.add("data")
            if (modifierList.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD)) modifiers.add("suspend")
        }
        return modifiers
    }

    fun inferKind(element: PsiElement): ElementKind {
        return when (element) {
            is KtFile -> ElementKind.FILE
            is KtClass -> when {
                element.isInterface() -> ElementKind.INTERFACE
                element.isEnum() -> ElementKind.ENUM
                else -> ElementKind.CLASS
            }
            is KtObjectDeclaration -> ElementKind.OBJECT
            is KtNamedFunction -> ElementKind.FUNCTION
            is KtProperty -> ElementKind.PROPERTY
            is KtPrimaryConstructor, is KtSecondaryConstructor -> ElementKind.CONSTRUCTOR
            else -> throw IllegalArgumentException("Unknown element type: ${element.javaClass}")
        }
    }
}