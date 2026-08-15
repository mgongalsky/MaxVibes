package com.maxvibes.domain.model.modification

import com.maxvibes.domain.model.code.ElementPath
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class BatchRolledBackTest {

    @Test
    fun `rollback error reports failed operation and reason`() {
        val error = ModificationError.BatchRolledBack(
            failedOperation = 2,
            reason = "REPLACE_ELEMENT postcondition failed"
        )

        assertEquals(
            "Modification batch was rolled back after operation 3 failed: " +
                    "REPLACE_ELEMENT postcondition failed",
            error.message
        )
    }

    @Test
    fun `rollback failure remains an unsuccessful modification result`() {
        val modification = Modification.ReplaceFile(
            targetPath = ElementPath.file("config.json"),
            newContent = "{}"
        )
        val result = ModificationResult.Failure(
            modification = modification,
            error = ModificationError.BatchRolledBack(0, "write failed")
        )

        assertFalse(result.success)
        assertIs<ModificationError.BatchRolledBack>(result.error)
        assertEquals(modification, result.modification)
    }
}
