package com.maxvibes.domain.model.modification

/**
 * Category of a [Modification] for display purposes.
 *
 * FILE_LEVEL    → blue  (CreateFile, ReplaceFile, DeleteFile)
 * ELEMENT_LEVEL → green (CreateElement, ReplaceElement, DeleteElement)
 * IMPORT        → yellow (AddImport, RemoveImport)
 */
enum class ModificationCategory { FILE_LEVEL, ELEMENT_LEVEL, IMPORT }

/**
 * Lightweight record of a successfully applied modification, persisted in [ChatMessage]
 * so the bubble footer can be reconstructed after IDE restart.
 *
 * @param path     String representation of the affected [ElementPath].
 * @param category Visual category used for colour-coding in the UI.
 */
data class AppliedModInfo(
    val path: String,
    val category: ModificationCategory
)

/** Derives the [ModificationCategory] from a [Modification] instance. */
fun Modification.toCategory(): ModificationCategory = when (this) {
    is Modification.CreateFile, is Modification.ReplaceFile, is Modification.DeleteFile,
    is Modification.MoveElement ->
        ModificationCategory.FILE_LEVEL

    is Modification.CreateElement, is Modification.ReplaceElement, is Modification.DeleteElement,
    is Modification.RenameElement, is Modification.SafeDelete ->
        ModificationCategory.ELEMENT_LEVEL

    is Modification.AddImport, is Modification.RemoveImport ->
        ModificationCategory.IMPORT
}
