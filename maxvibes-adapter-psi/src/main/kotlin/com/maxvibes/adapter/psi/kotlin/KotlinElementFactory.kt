package com.maxvibes.adapter.psi.kotlin

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.maxvibes.domain.model.code.ElementKind
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Фабрика для создания Kotlin PSI элементов из текста
 */
class KotlinElementFactory(private val project: Project) {

    private val psiFactory: KtPsiFactory by lazy { KtPsiFactory(project) }

    fun createElementFromText(text: String, kind: ElementKind): PsiElement? {
        return try {
            when (kind) {
                ElementKind.FILE -> psiFactory.createFile(text)
                ElementKind.CLASS -> psiFactory.createClass(text)
                ElementKind.INTERFACE -> psiFactory.createClass(text) // interface is also KtClass
                ElementKind.OBJECT -> psiFactory.createObject(text)
                ElementKind.ENUM -> psiFactory.createClass(text)
                ElementKind.FUNCTION -> psiFactory.createFunction(text)
                ElementKind.PROPERTY -> psiFactory.createProperty(text)
                ElementKind.CONSTRUCTOR -> null // Handled separately
            }
        } catch (e: Exception) {
            null
        }
    }

    fun createClass(text: String) = psiFactory.createClass(text)

    fun createFunction(text: String) = psiFactory.createFunction(text)

    fun createProperty(text: String) = psiFactory.createProperty(text)

    fun createObject(text: String) = psiFactory.createObject(text)

    fun createFile(text: String) = psiFactory.createFile(text)

    fun createFile(name: String, text: String) = psiFactory.createFile(name, text)

    fun createNewLine() = psiFactory.createNewLine()

    fun createWhiteSpace(text: String = " ") = psiFactory.createWhiteSpace(text)
}