package com.maxvibes.application.testsupport

import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationError
import com.maxvibes.domain.model.modification.ModificationResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeCodeRepositoryTest {

    @Test
    fun `applyModifications records several element changes as one ordered batch`() = runBlocking {
        val repository = FakeCodeRepository()
        val modifications = listOf(
            Modification.ReplaceElement(
                targetPath = ElementPath("file:src/Main.kt/class[Game]/function[start]"),
                newContent = "fun start() = Unit"
            ),
            Modification.CreateElement(
                targetPath = ElementPath("file:src/Main.kt/class[Game]"),
                content = "private fun reset() = Unit",
                elementKind = ElementKind.FUNCTION
            ),
            Modification.DeleteElement(
                targetPath = ElementPath("file:src/Main.kt/class[Game]/property[obsolete]")
            )
        )

        val results = repository.applyModifications(modifications)

        assertEquals(listOf(modifications), repository.appliedBatches)
        assertEquals(modifications, results.map { it.modification })
        assertTrue(results.all { it is ModificationResult.Success })
    }

    @Test
    fun `modification outcomes are evaluated in batch order`() = runBlocking {
        val repository = FakeCodeRepository()
        val visited = mutableListOf<ElementPath>()
        repository.modificationOutcome = { modification ->
            visited += modification.targetPath
            ModificationResult.Success(modification, modification.targetPath, null)
        }
        val modifications: List<Modification> = listOf(
            Modification.ReplaceFile(ElementPath.file("config.json"), "{\"enabled\":true}"),
            Modification.ReplaceFile(ElementPath.file("README.md"), "updated"),
            Modification.ReplaceFile(ElementPath.file("settings.yaml"), "mode: safe")
        )

        repository.applyModifications(modifications)

        assertEquals(modifications.map { it.targetPath }, visited)
        assertEquals(1, repository.appliedBatches.size)
    }

    @Test
    fun `configured failure is preserved beside successful primitive outcomes`() = runBlocking {
        val repository = FakeCodeRepository()
        val failingPath = ElementPath.file("broken.json")
        repository.modificationOutcome = { modification ->
            if (modification.targetPath == failingPath) {
                ModificationResult.Failure(
                    modification,
                    ModificationError.BatchRolledBack(1, "postcondition failed")
                )
            } else {
                ModificationResult.Success(modification, modification.targetPath, null)
            }
        }
        val modifications: List<Modification> = listOf(
            Modification.ReplaceFile(ElementPath.file("first.txt"), "first"),
            Modification.ReplaceFile(failingPath, "invalid"),
            Modification.ReplaceFile(ElementPath.file("last.md"), "last")
        )

        val results = repository.applyModifications(modifications)

        assertIs<ModificationResult.Success>(results[0])
        val failure = assertIs<ModificationResult.Failure>(results[1])
        assertIs<ModificationError.BatchRolledBack>(failure.error)
        assertIs<ModificationResult.Success>(results[2])
        assertEquals(listOf(modifications), repository.appliedBatches)
    }
}
