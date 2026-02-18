package com.maxvibes.adapter.psi.kotlin

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.maxvibes.domain.model.code.ElementKind
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Фабрика для создания Kotlin PSI элементов из текста.
 *
 * Includes fallback parsing: if direct creation fails (e.g. text contains
 * multiple declarations), parses as a file fragment and extracts elements.
 */
class KotlinElementFactory(private val project: Project) {

    private val psiFactory: KtPsiFactory by lazy { KtPsiFactory(project) }

    fun createElementFromText(text: String, kind: ElementKind): PsiElement? {
        return try {
            when (kind) {
                ElementKind.FILE -> psiFactory.createFile(text)
                ElementKind.CLASS -> psiFactory.createClass(text)
                ElementKind.INTERFACE -> psiFactory.createClass(text)
                ElementKind.OBJECT -> psiFactory.createObject(text)
                ElementKind.ENUM -> psiFactory.createClass(text)
                ElementKind.FUNCTION -> psiFactory.createFunction(text)
                ElementKind.PROPERTY -> psiFactory.createProperty(text)
                ElementKind.CONSTRUCTOR -> null
            }
        } catch (e: Exception) {
            println("[KotlinElementFactory] Direct creation failed for $kind: ${e.message}")
            println("[KotlinElementFactory] Trying file-fragment fallback...")
            createViaFileFallback(text, kind)
        }
    }

    /**
     * Fallback: parse text as a file fragment, extract the first matching declaration.
     *
     * Handles cases where LLM sends multiple declarations in one block,
     * or where the text has leading comments that confuse direct parsing.
     */
    private fun createViaFileFallback(text: String, kind: ElementKind): PsiElement? {
        return try {
            val tempFile = psiFactory.createFile(text)
            val declarations = tempFile.declarations
            if (declarations.isEmpty()) {
                println("[KotlinElementFactory] Fallback: no declarations found in text")
                return null
            }
            println("[KotlinElementFactory] Fallback: found ${declarations.size} declaration(s), using first")
            declarations.first()
        } catch (e2: Exception) {
            println("[KotlinElementFactory] Fallback also failed: ${e2.message}")
            null
        }
    }

    /**
     * Parse text as a file fragment and return ALL declarations.
     * Used by PsiModifier to handle multi-declaration REPLACE_ELEMENT.
     */
    fun parseDeclarations(text: String): List<KtDeclaration> {
        return try {
            val tempFile = psiFactory.createFile(text)
            tempFile.declarations.toList()
        } catch (e: Exception) {
            println("[KotlinElementFactory] parseDeclarations failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Create an import directive PSI element.
     */
    fun createImportDirective(fqName: String, isAllUnder: Boolean = false): KtImportDirective {
        val importPath = ImportPath(FqName(fqName), isAllUnder)
        return psiFactory.createImportDirective(importPath)
    }

    fun createClass(text: String) = psiFactory.createClass(text)

    fun createFunction(text: String) = psiFactory.createFunction(text)

    fun createProperty(text: String) = psiFactory.createProperty(text)

    fun createObject(text: String) = psiFactory.createObject(text)

    fun createFile(text: String) = psiFactory.createFile(text)

    fun createFile(name: String, text: String) = psiFactory.createFile(name, text)

    fun createNewLine() = psiFactory.createNewLine()

    fun createNewLine(count: Int): PsiElement = psiFactory.createNewLine(count)

    fun createWhiteSpace(text: String = " ") = psiFactory.createWhiteSpace(text)
}