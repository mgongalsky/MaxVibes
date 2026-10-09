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
import com.maxvibes.domain.model.modification.ModificationError
import com.maxvibes.domain.model.modification.ModificationResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PythonImportRequestTest {
    @Test
    fun reportedExplicitImportsPreserveEarlierChanges() = withFixture { fixture ->
        val file = fixture.addFileToProject("reported.py", "from PyQt6.QtWidgets import QComboBox\nvalue = 1\n") as PyFile
        val path = ElementPath.file(file.virtualFile.path)
        val requests = listOf(
            "from normalization import detail_settings",
            "from PyQt6.QtWidgets import QButtonGroup, QHBoxLayout, QLabel, QPushButton, QWidget",
            "from PyQt6.QtCore import pyqtSignal"
        )
        val results = runBlocking {
            PyCodeRepository(fixture.project).applyModifications(
                listOf(Modification.ReplaceFile(path, "from PyQt6.QtWidgets import QComboBox\nvalue = 2\n")) +
                    requests.map { Modification.AddImport(path, it) }
            )
        }
        assertTrue(results.all { it is ModificationResult.Success }, results.toString())
        val parsed = reparse(fixture, file)
        assertTrue(file.text.contains("value = 2"), file.text)
        for (name in listOf("QComboBox", "QButtonGroup", "QHBoxLayout", "QLabel", "QPushButton", "QWidget")) {
            assertTrue(PythonImportLookup.contains(parsed, "PyQt6.QtWidgets.$name"), file.text)
        }
        assertTrue(PythonImportLookup.contains(parsed, "normalization.detail_settings"), file.text)
        assertTrue(PythonImportLookup.contains(parsed, "PyQt6.QtCore.pyqtSignal"), file.text)
    }

    @Test
    fun relativeRemovalPreservesAbsoluteImportAndOtherNames() = withFixture { fixture ->
        val file = fixture.addFileToProject(
            "relative.py",
            "from raw_spectra import RawSpectraPanel\nfrom .raw_spectra import RawSpectraPanel, Keep\nfrom .. import Parent, Other\n"
        ) as PyFile
        val path = ElementPath.file(file.virtualFile.path)
        val results = runBlocking {
            PyCodeRepository(fixture.project).applyModifications(listOf(
                Modification.RemoveImport(path, ".raw_spectra.RawSpectraPanel"),
                Modification.RemoveImport(path, "..Parent")
            ))
        }
        assertTrue(results.all { it is ModificationResult.Success }, results.toString())
        val parsed = reparse(fixture, file)
        assertTrue(PythonImportLookup.contains(parsed, "raw_spectra.RawSpectraPanel"), file.text)
        assertTrue(PythonImportLookup.contains(parsed, ".raw_spectra.Keep"), file.text)
        assertTrue(PythonImportLookup.contains(parsed, "..Other"), file.text)
        assertFalse(PythonImportLookup.contains(parsed, ".raw_spectra.RawSpectraPanel"), file.text)
        assertFalse(PythonImportLookup.contains(parsed, "..Parent"), file.text)
    }

    @Test
    fun explicitAliasesFormsAndRelativeImportsSurviveRoundTrip() = withFixture { fixture ->
        val requests = listOf(
            "import os.path as paths",
            "from m import (A as Alias, B)",
            "from .m import Relative as Renamed",
            "from .. import Parent",
            ".Sibling"
        )
        requests.forEachIndexed { index, request ->
            val original = "\"\"\"Module documentation.\"\"\"\nfrom __future__ import annotations\nvalue = 1\n"
            val file = fixture.addFileToProject("roundtrip_$index.py", original) as PyFile
            val path = ElementPath.file(file.virtualFile.path)
            val repository = PyCodeRepository(fixture.project)
            val added = runBlocking { repository.applyModifications(listOf(Modification.AddImport(path, request))) }
            assertTrue(added.single() is ModificationResult.Success, "$request: $added")
            assertTrue(PythonImportLookup.contains(reparse(fixture, file), request), file.text)
            assertTrue(file.text.startsWith("\"\"\"Module documentation.\"\"\""), file.text)
            val afterFirstAdd = file.text
            val repeated = runBlocking { repository.applyModifications(listOf(Modification.AddImport(path, request))) }
            assertTrue(repeated.single() is ModificationResult.Success, repeated.toString())
            assertEquals(afterFirstAdd, file.text)
            val removed = runBlocking { repository.applyModifications(listOf(Modification.RemoveImport(path, request))) }
            assertTrue(removed.single() is ModificationResult.Success, "$request: $removed")
            assertFalse(PythonImportLookup.containsAny(reparse(fixture, file), request), file.text)
            assertTrue(file.text.contains("from __future__ import annotations"), file.text)
            assertTrue(file.text.contains("value = 1"), file.text)
        }
    }

    @Test
    fun aliasRemovalDoesNotRemoveOtherBindingsAndRemovesDuplicates() = withFixture { fixture ->
        val file = fixture.addFileToProject(
            "aliases.py", "from m import B as First, B as Second, Keep\nfrom m import B as First\n"
        ) as PyFile
        val result = runBlocking {
            PyCodeRepository(fixture.project).applyModifications(listOf(
                Modification.RemoveImport(ElementPath.file(file.virtualFile.path), "from m import B as First")
            ))
        }
        assertTrue(result.single() is ModificationResult.Success, result.toString())
        val parsed = reparse(fixture, file)
        assertFalse(PythonImportLookup.contains(parsed, "from m import B as First"), file.text)
        assertTrue(PythonImportLookup.contains(parsed, "from m import B as Second"), file.text)
        assertTrue(PythonImportLookup.contains(parsed, "m.Keep"), file.text)
    }

    @Test
    fun partialPresenceDoesNotSatisfyAdditionButIsDetectedForRemoval() = withFixture { fixture ->
        val parsed = PythonElementFactory(fixture.project).createFile("from m import A\n")
        assertFalse(PythonImportLookup.contains(parsed, "from m import A, B"))
        assertTrue(PythonImportLookup.containsAny(parsed, "from m import A, B"))
        assertFalse(PythonImportLookup.contains(parsed, "from m import A as Alias"))
        assertFalse(PythonImportLookup.contains(parsed, "import m.A"))
    }

    @Test
    fun invalidRequestsAndMissingRemovalRestoreEarlierChanges() = withFixture { fixture ->
        val original = "from m import A\nvalue = 1\n"
        val file = fixture.addFileToProject("invalid.py", original) as PyFile
        val path = ElementPath.file(file.virtualFile.path)
        val failures = listOf(
            Modification.AddImport(path, "from m import A\nvalue = 999"),
            Modification.AddImport(path, "from m import *"),
            Modification.AddImport(path, "from m import ("),
            Modification.AddImport(path, "m..A"),
            Modification.RemoveImport(path, "from m import A, Missing")
        )
        for (failure in failures) {
            val results = runBlocking {
                PyCodeRepository(fixture.project).applyModifications(listOf(
                    Modification.ReplaceFile(path, "from m import A\nvalue = 2\n"), failure
                ))
            }
            assertEquals(2, results.size)
            results.forEach {
                val error = assertIs<ModificationResult.Failure>(it).error
                assertEquals(1, assertIs<ModificationError.BatchRolledBack>(error).failedOperation)
            }
            assertEquals(original, file.text)
        }
    }

    private fun reparse(fixture: CodeInsightTestFixture, file: PyFile): PyFile {
        val parsed = PythonElementFactory(fixture.project).createFile(file.text)
        assertNull(PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java), file.text)
        return parsed
    }

    private fun withFixture(action: (CodeInsightTestFixture) -> Unit) {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            val factory = IdeaTestFixtureFactory.getFixtureFactory()
            val projectFixture = factory.createLightFixtureBuilder("PythonImportRequestTest").fixture
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
