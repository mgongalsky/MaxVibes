package com.maxvibes.adapter.psi.kotlin

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.maxvibes.domain.model.code.ElementKind
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.ImportPath

/** Creates validated Kotlin PSI declarations from protocol text. */
class KotlinElementFactory(private val project: Project) {

    private val psiFactory: KtPsiFactory by lazy { KtPsiFactory(project) }

    fun createElementFromText(text: String, kind: ElementKind): PsiElement? {
        val direct = runCatching {
            when (kind) {
                ElementKind.FILE -> psiFactory.createFile(text)
                ElementKind.CLASS -> psiFactory.createClass(text)
                ElementKind.INTERFACE -> psiFactory.createClass(text)
                ElementKind.OBJECT -> psiFactory.createObject(text)
                ElementKind.ENUM -> psiFactory.createClass(text)
                ElementKind.ENUM_ENTRY -> createEnumEntryWithLeadingTrivia(text)
                ElementKind.FUNCTION -> psiFactory.createFunction(text)
                ElementKind.PROPERTY -> psiFactory.createProperty(text)
                ElementKind.INIT -> createInitBlock(text)
                ElementKind.CONSTRUCTOR -> null
            }
        }.getOrNull()

        if (direct != null && matchesKind(direct, kind) && !hasSyntaxErrors(direct)) return direct
        return createViaFileFallback(text, kind)
    }

    private fun createViaFileFallback(text: String, kind: ElementKind): PsiElement? {
        if (kind == ElementKind.FILE || kind == ElementKind.ENUM_ENTRY || kind == ElementKind.INIT) return null
        val file = runCatching { psiFactory.createFile(text) }.getOrNull() ?: return null
        if (hasSyntaxErrors(file) || file.declarations.size != 1) return null
        return file.declarations.single().takeIf { matchesKind(it, kind) }
    }

    private fun createInitBlock(text: String): KtAnonymousInitializer? {
        val wrapper = psiFactory.createClass("class __MaxVibesInitWrapper {\n$text\n}")
        if (hasSyntaxErrors(wrapper)) return null
        return wrapper.body?.anonymousInitializers?.singleOrNull()
    }

    private fun matchesKind(element: PsiElement, kind: ElementKind): Boolean = when (kind) {
        ElementKind.FILE -> element is KtFile
        ElementKind.CLASS -> element is KtClass && !element.isInterface() && !element.isEnum()
        ElementKind.INTERFACE -> element is KtClass && element.isInterface()
        ElementKind.OBJECT -> element is KtObjectDeclaration
        ElementKind.ENUM -> element is KtClass && element.isEnum()
        ElementKind.ENUM_ENTRY -> element is KtEnumEntry
        ElementKind.FUNCTION -> element is KtNamedFunction
        ElementKind.PROPERTY -> element is KtProperty
        ElementKind.CONSTRUCTOR -> element is KtConstructor<*>
        ElementKind.INIT -> element is KtAnonymousInitializer
    }

    private fun hasSyntaxErrors(element: PsiElement): Boolean =
        element is PsiErrorElement || PsiTreeUtil.findChildOfType(element, PsiErrorElement::class.java) != null

    fun parseDeclarations(text: String): List<KtDeclaration> {
        val file = runCatching { psiFactory.createFile(text) }.getOrNull() ?: return emptyList()
        return if (hasSyntaxErrors(file)) emptyList() else file.declarations.toList()
    }

    fun createImportDirective(fqName: String, isAllUnder: Boolean = false): KtImportDirective =
        psiFactory.createImportDirective(ImportPath(FqName(fqName), isAllUnder))

    fun createClass(text: String) = psiFactory.createClass(text)
    fun createFunction(text: String) = psiFactory.createFunction(text)
    fun createProperty(text: String) = psiFactory.createProperty(text)
    fun createObject(text: String) = psiFactory.createObject(text)
    fun createFile(text: String) = psiFactory.createFile(text)
    fun createFile(name: String, text: String) = psiFactory.createFile(name, text)
    fun createNewLine() = psiFactory.createNewLine()
    fun createNewLine(count: Int): PsiElement = psiFactory.createNewLine(count)
    fun createWhiteSpace(text: String = " ") = psiFactory.createWhiteSpace(text)

    fun getElementName(element: PsiElement): String? =
        (element as? KtNamedDeclaration)?.name

    private fun createEnumEntryWithLeadingTrivia(text: String): PsiElement? {
        val wrapper = psiFactory.createFile("enum class __MaxVibesEnumWrapper {\n$text\n}")
        if (hasSyntaxErrors(wrapper)) return null
        val enumClass = wrapper.declarations.filterIsInstance<KtClass>().firstOrNull() ?: return null
        return enumClass.declarations.filterIsInstance<KtEnumEntry>().singleOrNull()
    }
}
