// maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/modification/Modification.kt
package com.maxvibes.domain.model.modification

import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath

enum class InsertPosition {
    BEFORE, AFTER, FIRST_CHILD, LAST_CHILD
}

sealed interface Modification {
    val targetPath: ElementPath

    // ═══════════════════════════════════════════════════
    // File-level operations
    // ═══════════════════════════════════════════════════

    data class CreateFile(
        override val targetPath: ElementPath,
        val content: String
    ) : Modification

    data class ReplaceFile(
        override val targetPath: ElementPath,
        val newContent: String
    ) : Modification

    data class DeleteFile(
        override val targetPath: ElementPath
    ) : Modification

    // ═══════════════════════════════════════════════════
    // Element-level operations
    // ═══════════════════════════════════════════════════

    data class CreateElement(
        override val targetPath: ElementPath,
        val elementKind: ElementKind,
        val content: String,
        val position: InsertPosition = InsertPosition.LAST_CHILD
    ) : Modification

    data class ReplaceElement(
        override val targetPath: ElementPath,
        val newContent: String
    ) : Modification

    data class DeleteElement(
        override val targetPath: ElementPath
    ) : Modification

    // ═══════════════════════════════════════════════════
    // Import operations (lightweight, no full file rewrite)
    // ═══════════════════════════════════════════════════

    /**
     * Add an import statement to a file.
     * targetPath = file path (e.g. file:src/main/kotlin/User.kt)
     * importPath = fully qualified import (e.g. "com.example.dto.UserDTO")
     */
    data class AddImport(
        override val targetPath: ElementPath,
        val importPath: String
    ) : Modification

    /**
     * Remove an import statement from a file.
     * targetPath = file path
     * importPath = fully qualified import to remove
     */
    data class RemoveImport(
        override val targetPath: ElementPath,
        val importPath: String
    ) : Modification
}

sealed interface ModificationResult {
    val modification: Modification
    val success: Boolean

    data class Success(
        override val modification: Modification,
        val affectedPath: ElementPath,
        val resultContent: String?
    ) : ModificationResult {
        override val success = true
    }

    data class Failure(
        override val modification: Modification,
        val error: ModificationError
    ) : ModificationResult {
        override val success = false
    }
}

sealed class ModificationError(val message: String) {
    class ElementNotFound(path: ElementPath) :
        ModificationError("Element not found: ${path.value}")

    class FileNotFound(path: String) :
        ModificationError("File not found: $path")

    class ParseError(details: String) :
        ModificationError("Failed to parse code: $details")

    class InvalidOperation(reason: String) :
        ModificationError("Invalid operation: $reason")

    class IOError(cause: String) :
        ModificationError("IO error: $cause")
}