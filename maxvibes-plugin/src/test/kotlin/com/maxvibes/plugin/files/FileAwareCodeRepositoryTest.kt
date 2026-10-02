package com.maxvibes.plugin.files

import com.intellij.openapi.project.Project
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.domain.model.code.*
import com.maxvibes.domain.model.modification.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class FileAwareCodeRepositoryTest {
    private val language = mockk<CodeRepository>()
    private val contents = linkedMapOf<String, String>()
    private lateinit var repository: FileAwareCodeRepository

    @BeforeEach
    fun setUp() {
        mockkConstructor(ProjectTextFiles::class)
        every { anyConstructed<ProjectTextFiles>().read<Any?>(any()) } answers {
            firstArg<() -> Any?>().invoke()
        }
        every { anyConstructed<ProjectTextFiles>().write<Any?>(any()) } answers {
            firstArg<() -> Any?>().invoke()
        }
        every { anyConstructed<ProjectTextFiles>().resolve(any()) } answers { Path.of(firstArg<String>()) }
        every { anyConstructed<ProjectTextFiles>().capture(any()) } answers {
            val path = firstArg<String>()
            val text = contents[path]
            ProjectTextFiles.Snapshot(path, text?.toByteArray(), Charsets.UTF_8, text, false)
        }
        every { anyConstructed<ProjectTextFiles>().find(any()) } answers {
            if (contents.containsKey(firstArg<String>())) mockk() else null
        }
        every { anyConstructed<ProjectTextFiles>().text(any()) } answers {
            contents[firstArg<String>()] ?: error("File not found")
        }
        every { anyConstructed<ProjectTextFiles>().create(any(), any()) } answers {
            val path = firstArg<String>()
            check(!contents.containsKey(path)) { "File already exists" }
            contents[path] = secondArg()
        }
        every { anyConstructed<ProjectTextFiles>().replace(any<String>(), any()) } answers {
            val path = firstArg<String>()
            check(contents.containsKey(path)) { "File not found" }
            contents[path] = secondArg()
        }
        every { anyConstructed<ProjectTextFiles>().delete(any()) } answers {
            check(contents.remove(firstArg<String>()) != null)
        }
        every { anyConstructed<ProjectTextFiles>().save(any()) } just Runs
        every { anyConstructed<ProjectTextFiles>().restore(any()) } answers {
            firstArg<List<ProjectTextFiles.Snapshot>>().forEach {
                if (it.bytes == null) contents.remove(it.path)
                else contents[it.path] = requireNotNull(it.documentText)
            }
            emptyList()
        }
        repository = FileAwareCodeRepository(mockk<Project>(), language)
    }

    @AfterEach
    fun tearDown() = unmockkConstructor(ProjectTextFiles::class)

    @Test
    fun `configuration files can be created read replaced and deleted without a language plugin`() = runBlocking {
        for (name in listOf(
            "pytest-unit.ini",
            "settings.json",
            "pyproject.toml",
            "README.md",
            ".gitignore",
            "layout.xml"
        )) {
            val path = ElementPath.file(name)
            assertTrue(repository.applyModification(Modification.CreateFile(path, "first\n")).success)
            assertEquals("first\n", repository.getCodeView(CodeViewRequest(name, CodeGranularity.FULL)).content)
            assertTrue(repository.applyModification(Modification.ReplaceFile(path, "  second\n")).success)
            assertEquals("  second\n", contents[name])
            assertTrue(repository.applyModification(Modification.DeleteFile(path)).success)
            assertFalse(repository.exists(path))
        }
        coVerify(exactly = 0) { language.applyModifications(any()) }
        coVerify(exactly = 0) { language.getCodeView(any()) }
    }

    @Test
    fun `creating an existing file preserves its original contents`() = runBlocking {
        contents["config.ini"] = "original"
        val result =
            repository.applyModification(Modification.CreateFile(ElementPath.file("config.ini"), "replacement"))
        assertFalse(result.success)
        assertEquals("original", contents["config.ini"])
    }

    @Test
    fun `failed later operation removes the newly created non language file`() = runBlocking {
        val results = repository.applyModifications(
            listOf(
                Modification.CreateFile(ElementPath.file("pytest-unit.ini"), "[pytest]\n"),
                Modification.ReplaceFile(ElementPath.file("missing.md"), "fails")
            )
        )
        assertTrue(contents.isEmpty())
        assertTrue(results.all { it is ModificationResult.Failure && it.error is ModificationError.BatchRolledBack })
        assertEquals(1, (results.first() as ModificationResult.Failure).let {
            (it.error as ModificationError.BatchRolledBack).failedOperation
        })
    }

    @Test
    fun `incomplete restoration never claims the batch was rolled back`() = runBlocking {
        every { anyConstructed<ProjectTextFiles>().restore(any()) } returns listOf("pytest-unit.ini: access denied")
        val result = repository.applyModification(Modification.ReplaceFile(ElementPath.file("missing.md"), "fails"))
        val error = assertIs<ModificationResult.Failure>(result).error
        assertIs<ModificationError.IOError>(error)
        assertContains(error.message, "NOT restored")
        assertContains(error.message, "pytest-unit.ini")
        assertFalse(error.message.contains("batch was rolled back"))
    }

    @Test
    fun `snapshot failure prevents every mutation`() = runBlocking {
        every { anyConstructed<ProjectTextFiles>().capture("protected.ini") } throws IllegalStateException("Cannot read")
        val result = repository.applyModification(Modification.ReplaceFile(ElementPath.file("protected.ini"), "new"))
        assertFalse(result.success)
        verify(exactly = 0) { anyConstructed<ProjectTextFiles>().replace(any<String>(), any()) }
        verify(exactly = 0) { anyConstructed<ProjectTextFiles>().restore(any()) }
    }

    @Test
    fun `save failure restores the snapshot`() = runBlocking {
        contents["notes.md"] = "old"
        every { anyConstructed<ProjectTextFiles>().save(any()) } throws IllegalStateException("Disk full")
        val result = repository.applyModification(Modification.ReplaceFile(ElementPath.file("notes.md"), "new"))
        assertFalse(result.success)
        assertEquals("old", contents["notes.md"])
    }

    @Test
    fun `rename preserves content without invoking the language adapter`() = runBlocking {
        contents["old.md"] = "# Notes\n"
        every { anyConstructed<ProjectTextFiles>().rename("old.md", "new.md") } answers {
            contents["new.md"] = requireNotNull(contents.remove("old.md"))
            "new.md"
        }
        val result = repository.applyModification(Modification.RenameElement(ElementPath.file("old.md"), "new.md"))
        assertTrue(result.success)
        assertEquals("# Notes\n", contents["new.md"])
        assertFalse(contents.containsKey("old.md"))
        coVerify(exactly = 0) { language.applyModifications(any()) }
    }

    @Test
    fun `failed operation after rename restores the old path and removes the new path`() = runBlocking {
        contents["old.md"] = "original"
        every { anyConstructed<ProjectTextFiles>().rename("old.md", "new.md") } answers {
            contents["new.md"] = requireNotNull(contents.remove("old.md"))
            "new.md"
        }
        val results = repository.applyModifications(
            listOf(
                Modification.RenameElement(ElementPath.file("old.md"), "new.md"),
                Modification.ReplaceFile(ElementPath.file("missing.ini"), "fails")
            )
        )
        assertTrue(results.none { it.success })
        assertEquals(mapOf("old.md" to "original"), contents)
    }

    @Test
    fun `move preserves filename and content`() = runBlocking {
        val target = Path.of("docs", "notes.md").toString()
        contents["notes.md"] = "notes"
        every { anyConstructed<ProjectTextFiles>().move("notes.md", "docs") } answers {
            contents[target] = requireNotNull(contents.remove("notes.md"))
            target
        }
        val result = repository.applyModification(Modification.MoveElement(ElementPath.file("notes.md"), "docs"))
        assertTrue(result.success)
        assertEquals(mapOf(target to "notes"), contents)
        assertEquals(target, assertIs<ModificationResult.Success>(result).affectedPath.filePath)
    }
}
