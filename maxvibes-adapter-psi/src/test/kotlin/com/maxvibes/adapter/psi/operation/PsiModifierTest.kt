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
        val jsonType = mockk<FileType>()
        val oldFirst = mockk<PsiElement>()
        val oldLast = mockk<PsiElement>()
        val sourceChild = mockk<PsiElement>()
        val copiedChild = mockk<PsiElement>()
        val targetFile = mockk<PsiFile>(relaxed = true)
        val parsedFile = mockk<PsiFile>()
        val forwardedType = slot<FileType>()
        val newContent = "{\"enabled\":true}"

        every { targetFile.name } returns "config.json"
        every { targetFile.fileType } returns jsonType
        every { targetFile.firstChild } returns oldFirst
        every { targetFile.lastChild } returns oldLast
        every { parsedFile.children } returns arrayOf(sourceChild)
        every { sourceChild.copy() } returns copiedChild
        every {
            psiFileFactory.createFileFromText("config.json", capture(forwardedType), newContent)
        } returns parsedFile

        val result = PsiModifier(project, elementFactory)
            .replaceFileContent(targetFile, newContent)

        assertSame(targetFile, result)
        assertSame(jsonType, forwardedType.captured, "replaceFileContent must forward the file's own type")
        verify(exactly = 1) { targetFile.deleteChildRange(oldFirst, oldLast) }
        verify(exactly = 1) { targetFile.add(copiedChild) }
        verify(exactly = 1) { codeStyleManager.reformat(targetFile) }
    }

    @Test
    fun `replaceFileContent supports an empty original file`() {
        val markdownType = mockk<FileType>()
        val targetFile = mockk<PsiFile>(relaxed = true)
        val parsedFile = mockk<PsiFile>()
        val forwardedType = slot<FileType>()

        every { targetFile.name } returns "notes.md"
        every { targetFile.fileType } returns markdownType
        every { targetFile.firstChild } returns null
        every { targetFile.lastChild } returns null
        every { parsedFile.children } returns emptyArray()
        every {
            psiFileFactory.createFileFromText("notes.md", capture(forwardedType), "")
        } returns parsedFile

        val result = PsiModifier(project, elementFactory)
            .replaceFileContent(targetFile, "")

        assertSame(targetFile, result)
        assertSame(markdownType, forwardedType.captured, "replaceFileContent must forward the file's own type")
        verify(exactly = 0) {
            targetFile.deleteChildRange(any<PsiElement>(), any<PsiElement>())
        }
        verify(exactly = 1) { codeStyleManager.reformat(targetFile) }
    }

    @Test
    fun `replaceElement rejects content carrying more than one declaration`() {
        val target = mockk<PsiElement>()
        val content = "fun first() = 1\n\nfun second() = 2"

        every { elementFactory.parseDeclarations(content) } returns
                listOf(mockk<KtDeclaration>(), mockk<KtDeclaration>())

        val result = PsiModifier(project, elementFactory)
            .replaceElement(target, content, ElementKind.FUNCTION)

        assertNull(result)
        verify(exactly = 0) { elementFactory.createElementFromText(any(), any()) }
    }

    @Test
    fun `replaceElement leaves the target untouched when the factory fails`() {
        val target = mockk<PsiElement>()
        val content = "fun first() = 1"

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
        verify(exactly = 0) { parent.add(any<PsiElement>()) }
    }
}
