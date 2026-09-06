package com.maxvibes.adapter.psi.operation

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.maxvibes.adapter.psi.kotlin.KotlinElementFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertSame
import com.maxvibes.domain.model.code.ElementKind
import org.jetbrains.kotlin.psi.KtDeclaration
import kotlin.test.assertNull
import kotlin.test.assertFalse
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.kotlin.name.FqName
import com.maxvibes.domain.model.modification.InsertPosition
import org.jetbrains.kotlin.psi.KtClassOrObject

class PsiModifierTest {
    private val project = mockk<Project>()
    private val elementFactory = mockk<KotlinElementFactory>()
    private val psiFileFactory = mockk<PsiFileFactory>()
    private val codeStyleManager = mockk<CodeStyleManager>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(PsiFileFactory::class)
        mockkStatic(CodeStyleManager::class)
        every { PsiFileFactory.getInstance(project) } returns psiFileFactory
        every { CodeStyleManager.getInstance(project) } returns codeStyleManager
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(PsiFileFactory::class)
        unmockkStatic(CodeStyleManager::class)
    }

    @Test
    fun `replaceFileContent forwards resolved file type and replaces child range`() {
        // Replacement now updates the existing document, preserving the file and its type.
        val targetFile = mockk<PsiFile>()
        val document = mockk<com.intellij.openapi.editor.Document>(relaxed = true)
        val manager = mockk<com.intellij.psi.PsiDocumentManager>(relaxed = true)
        every { targetFile.name } returns "config.json"
        every { project.getService(com.intellij.psi.PsiDocumentManager::class.java) } returns manager
        every { manager.getDocument(targetFile) } returns document

        val result = PsiModifier(project, elementFactory)
            .replaceFileContent(targetFile, "{\r\n\"enabled\":true\r\n}")

        assertSame(targetFile, result)
        io.mockk.verifyOrder {
            manager.doPostponedOperationsAndUnblockDocument(document)
            document.setText("{\n\"enabled\":true\n}")
            manager.commitDocument(document)
            codeStyleManager.reformat(targetFile)
        }
        verify(exactly = 1) { document.setText(any()) }
        verify(exactly = 1) { manager.commitDocument(document) }
        verify(exactly = 0) { targetFile.deleteChildRange(any<PsiElement>(), any<PsiElement>()) }
        verify(exactly = 0) { targetFile.add(any<PsiElement>()) }
    }

    @Test
    fun `replaceFileContent supports an empty original file`() {
        val targetFile = mockk<PsiFile>()
        val document = mockk<com.intellij.openapi.editor.Document>(relaxed = true)
        val manager = mockk<com.intellij.psi.PsiDocumentManager>(relaxed = true)
        every { targetFile.name } returns "notes.md"
        every { document.text } returns ""
        every { project.getService(com.intellij.psi.PsiDocumentManager::class.java) } returns manager
        every { manager.getDocument(targetFile) } returns document

        val result = PsiModifier(project, elementFactory)
            .replaceFileContent(targetFile, "", reformat = false)

        assertSame(targetFile, result)
        io.mockk.verifyOrder {
            manager.doPostponedOperationsAndUnblockDocument(document)
            document.setText("")
            manager.commitDocument(document)
        }
        verify(exactly = 1) { document.setText("") }
        verify(exactly = 1) { manager.commitDocument(document) }
        verify(exactly = 0) { codeStyleManager.reformat(any<PsiElement>()) }
    }

    @Test
    fun `replaceElement rejects content carrying more than one declaration`() {
        val target = mockk<PsiElement>()
        val content = "fun first() = 1\n\nfun second() = 2"
        every { target.isValid } returns true
        every { elementFactory.parseDeclarations(content) } returns
                listOf(mockk<KtDeclaration>(), mockk<KtDeclaration>())

        val result = PsiModifier(project, elementFactory)
            .replaceElement(target, content, ElementKind.FUNCTION)

        assertNull(result)
        verify(exactly = 0) { elementFactory.createElementFromText(any(), any()) }
        verify(exactly = 0) { target.replace(any<PsiElement>()) }
    }

    @Test
    fun `replaceElement leaves the target untouched when the factory fails`() {
        val target = mockk<PsiElement>()
        val content = "fun first() = 1"
        every { target.isValid } returns true
        every { elementFactory.parseDeclarations(content) } returns listOf(mockk<KtDeclaration>())
        every { elementFactory.createElementFromText(content, ElementKind.FUNCTION) } returns null

        val result = PsiModifier(project, elementFactory)
            .replaceElement(target, content, ElementKind.FUNCTION)

        assertNull(result)
        verify(exactly = 0) { target.replace(any<PsiElement>()) }
    }

    @Test
    fun `deleteElement reports failure when the element survives deletion`() {
        val element = mockk<PsiElement>(relaxed = true)

        every { element.nextSibling } returns null
        every { element.isValid } returns true

        val deleted = PsiModifier(project, elementFactory).deleteElement(element)

        assertFalse(deleted, "deleteElement must not report success while the element is still valid")
        verify(exactly = 1) { element.delete() }
    }

    @Test
    fun `addImport skips an import that is already present`() {
        val fqName = "kotlin.collections.List"
        val file = mockk<KtFile>()
        val existing = mockk<KtImportDirective>()

        every { file.name } returns "Sample.kt"
        every { existing.importPath } returns ImportPath(FqName(fqName), false)
        every { file.importDirectives } returns listOf(existing)

        val added = PsiModifier(project, elementFactory).addImport(file, fqName)

        assertNull(added)
        verify(exactly = 0) { elementFactory.createImportDirective(any(), any()) }
    }

    @Test
    fun `removeImport reports failure when the import is absent`() {
        val file = mockk<KtFile>()

        every { file.name } returns "Sample.kt"
        every { file.importDirectives } returns emptyList()

        val removed = PsiModifier(project, elementFactory)
            .removeImport(file, "kotlin.collections.List")

        assertFalse(removed, "an absent import must not be reported as removed")
        verify(exactly = 0) { file.importList }
    }

    @Test
    fun `addElement replaces a declaration with the same name instead of duplicating it`() {
        val parent = mockk<KtClassOrObject>()
        val existing = mockk<KtDeclaration>(relaxed = true)
        val created = mockk<PsiElement>(relaxed = true)
        val copied = mockk<PsiElement>(relaxed = true)
        val replaced = mockk<PsiElement>(relaxed = true)
        val content = "fun render() = Unit"

        every { parent.isValid } returns true
        every { elementFactory.createElementFromText(content, ElementKind.FUNCTION) } returns created
        every { elementFactory.getElementName(created) } returns "render"
        every { elementFactory.getElementName(existing) } returns "render"
        every { parent.declarations } returns listOf(existing)
        every { existing.prevSibling } returns null
        every { created.copy() } returns copied
        every { existing.replace(copied) } returns replaced
        every { replaced.prevSibling } returns null

        val result = PsiModifier(project, elementFactory)
            .addElement(parent, content, ElementKind.FUNCTION, InsertPosition.LAST_CHILD)

        assertSame(replaced, result)
        verify(exactly = 1) { existing.replace(copied) }
        verify(exactly = 0) { parent.add(any<PsiElement>()) }
    }
}
