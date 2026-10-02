package com.maxvibes.adapter.psi.python

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.jetbrains.python.psi.PyFile
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PythonImportPostconditionTest {
    @Test
    fun recognizesEachImportRegardlessOfFormattingAndAliases() = withFixture { fixture ->
        val cases = listOf(
            "from m import A, B\n" to "m.B",
            "from m import (\n    A,  # first\n    B,\n)\n" to "m.B",
            "from m import B as Alias\n" to "m.B",
            "import os, m.B as Alias\n" to "m.B",
            "import os\n" to "os",
            "from .m import B\n" to ".m.B",
            "from .. import B\n" to "..B"
        )
        cases.forEach { (source, path) ->
            val file = PythonElementFactory(fixture.project).createFile(source)
            assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java), source)
            assertTrue(PythonImportLookup.contains(file, path), "$path in $source")
        }
    }

    @Test
    fun rejectsTextMatchesPrefixesOtherScopesAndRelativeImports() = withFixture { fixture ->
        val cases = listOf(
            "# from m import B\n",
            "text = 'from m import B'\n",
            "from m import Bigger\n",
            "from other.m import B\n",
            "import m.Bigger\n",
            "from m import *\n",
            "from .m import B\n",
            "def local():\n    from m import B\n",
            "class Local:\n    from m import B\n"
        )
        cases.forEach { source ->
            val file = PythonElementFactory(fixture.project).createFile(source)
            assertFalse(PythonImportLookup.contains(file, "m.B"), source)
        }
    }

    @Test
    fun addingToExistingFromImportDoesNotRollBackEarlierChanges() = withFixture { fixture ->
        val file = fixture.addFileToProject("add_import.py", "from m import A\nvalue = 1\n") as PyFile
        val path = ElementPath.file(file.virtualFile.path)
        val results = runBlocking {
            PyCodeRepository(fixture.project).applyModifications(
                listOf(
                    Modification.ReplaceFile(path, "from m import A\nvalue = 2\n"),
                    Modification.AddImport(path, "m.B")
                )
            )
        }
        assertEquals(2, results.size)
        assertTrue(results.all { it is ModificationResult.Success }, results.toString())
        val parsed = PythonElementFactory(fixture.project).createFile(file.text)
        assertNull(PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java), file.text)
        assertTrue(PythonImportLookup.contains(parsed, "m.A"), file.text)
        assertTrue(PythonImportLookup.contains(parsed, "m.B"), file.text)
        assertTrue(file.text.contains("value = 2"), file.text)
    }

    @Test
    fun removingImportIgnoresCommentsAndLongerNames() = withFixture { fixture ->
        val file = fixture.addFileToProject(
            "remove_import.py",
            "from m import B, Bigger\n# from m import B\ntext = 'from m import B'\n"
        ) as PyFile
        val results = runBlocking {
            PyCodeRepository(fixture.project).applyModifications(
                listOf(Modification.RemoveImport(ElementPath.file(file.virtualFile.path), "m.B"))
            )
        }
        assertTrue(results.single() is ModificationResult.Success, results.toString())
        val parsed = PythonElementFactory(fixture.project).createFile(file.text)
        assertFalse(PythonImportLookup.contains(parsed, "m.B"), file.text)
        assertTrue(PythonImportLookup.contains(parsed, "m.Bigger"), file.text)
        assertTrue(file.text.contains("# from m import B"), file.text)
        assertTrue(file.text.contains("text = 'from m import B'"), file.text)
    }

    @Test
    fun failedRemovalStillRollsBackEarlierChanges() = withFixture { fixture ->
        val original = "from m import A\nvalue = 1\n"
        val file = fixture.addFileToProject("rollback.py", original) as PyFile
        val path = ElementPath.file(file.virtualFile.path)
        val results = runBlocking {
            PyCodeRepository(fixture.project).applyModifications(
                listOf(
                    Modification.ReplaceFile(path, "from m import A\nvalue = 2\n"),
                    Modification.RemoveImport(path, "m.B")
                )
            )
        }
        assertTrue(results.all { it is ModificationResult.Failure }, results.toString())
        assertEquals(original, file.text)
    }

    private fun withFixture(action: (CodeInsightTestFixture) -> Unit) {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val factory = IdeaTestFixtureFactory.getFixtureFactory()
            val projectFixture = factory.createLightFixtureBuilder("PythonImportPostconditionTest").fixture
            val fixture = factory.createCodeInsightFixture(projectFixture, TempDirTestFixtureImpl())
            fixture.setUp()
            try {
                action(fixture)
            } finally {
                fixture.tearDown()
            }
        }
    }
}
