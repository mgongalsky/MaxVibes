package com.maxvibes.plugin.files

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProjectTextFilesTest {
    @TempDir
    lateinit var root: Path
    private val project = mockk<Project>()
    private val vfs = mockk<LocalFileSystem>()
    private val documents = mockk<FileDocumentManager>()
    private val psiDocuments = mockk<PsiDocumentManager>(relaxed = true)
    private val virtualFiles = mutableMapOf<String, VirtualFile>()
    private lateinit var files: ProjectTextFiles

    private data class Editor(val file: VirtualFile, val document: Document, var text: String, var dirty: Boolean)

    @BeforeEach
    fun setUp() {
        mockkStatic(LocalFileSystem::class)
        mockkStatic(FileDocumentManager::class)
        every { LocalFileSystem.getInstance() } returns vfs
        every { FileDocumentManager.getInstance() } returns documents
        every { project.basePath } returns root.toString()
        every { project.getService(PsiDocumentManager::class.java) } returns psiDocuments
        every { vfs.findFileByPath(any()) } answers { virtualFiles[firstArg<String>()] }
        files = ProjectTextFiles(project)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(LocalFileSystem::class)
        unmockkStatic(FileDocumentManager::class)
    }

    private fun editor(name: String, disk: String, unsaved: String? = null): Editor {
        val path = root.resolve(name)
        Files.writeString(path, disk)
        val file = mockk<VirtualFile>(relaxed = true)
        val document = mockk<Document>()
        val editor = Editor(file, document, unsaved ?: disk, unsaved != null)
        virtualFiles[path.toString().replace('\\', '/')] = file
        every { file.isDirectory } returns false
        every { file.fileType.isBinary } returns false
        every { file.charset } returns Charsets.UTF_8
        every { documents.getDocument(file) } returns document
        every { documents.isDocumentUnsaved(document) } answers { editor.dirty }
        every { document.text } answers { editor.text }
        every { document.setText(any()) } answers {
            editor.text = firstArg<CharSequence>().toString()
            editor.dirty = true
        }
        every { file.setBinaryContent(any<ByteArray>()) } answers {
            Files.write(path, firstArg<ByteArray>())
            Unit
        }
        every { documents.reloadFromDisk(document) } answers {
            editor.text = Files.readString(path)
            editor.dirty = false
        }
        every { file.delete(any()) } answers {
            Files.delete(path)
            virtualFiles.remove(path.toString().replace('\\', '/'))
            Unit
        }
        return editor
    }

    @Test
    fun `rollback restores disk bytes and unsaved editor text independently`() {
        val original = "[pytest]\n  value = old\n"
        val pending = "[pytest]\n  value = unsaved\n"
        val editor = editor("pytest-unit.ini", original, pending)
        val snapshot = files.capture("pytest-unit.ini")
        Files.writeString(root.resolve("pytest-unit.ini"), "changed")
        editor.text = "changed"
        editor.dirty = false
        assertEquals(emptyList(), files.restore(listOf(snapshot)))
        assertEquals(original, Files.readString(root.resolve("pytest-unit.ini")))
        assertEquals(pending, editor.text)
        assertTrue(editor.dirty)
    }

    @Test
    fun `rollback removes a newly created non language file from disk`() {
        val snapshot = files.capture("new.md")
        editor("new.md", "created")
        assertEquals(emptyList(), files.restore(listOf(snapshot)))
        assertFalse(Files.exists(root.resolve("new.md")))
    }

    @Test
    fun `rollback continues after one failure and reports its path`() {
        val first = editor("first.ini", "first")
        val second = editor("second.md", "second")
        val snapshots = listOf(files.capture("first.ini"), files.capture("second.md"))
        Files.writeString(root.resolve("second.md"), "changed")
        second.text = "changed"
        every { first.file.setBinaryContent(any<ByteArray>()) } throws IllegalStateException("access denied")
        val errors = files.restore(snapshots)
        assertEquals(1, errors.size)
        assertContains(errors.single(), "first.ini")
        assertEquals("second", Files.readString(root.resolve("second.md")))
        assertEquals("second", second.text)
    }

    @Test
    fun `silent failed deletion is detected on disk`() {
        val snapshot = files.capture("new.ini")
        val editor = editor("new.ini", "created")
        every { editor.file.delete(any()) } just Runs
        val errors = files.restore(listOf(snapshot))
        assertEquals(1, errors.size)
        assertContains(errors.single(), "still exists")
    }

    @Test
    fun `outside paths and the project directory are rejected`() {
        assertFailsWith<IllegalArgumentException> { files.resolve("../outside.ini") }
        assertFailsWith<IllegalArgumentException> { files.resolve(root.toString()) }
        assertEquals(root.resolve("config.ini"), files.resolve("config.ini"))
    }
}
