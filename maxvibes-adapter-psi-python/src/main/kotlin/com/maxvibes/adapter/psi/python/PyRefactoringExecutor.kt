package com.maxvibes.adapter.psi.python

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.maxvibes.shared.result.Result

/**
 * Python counterpart of PsiRefactoringExecutor from maxvibes-adapter-psi.
 * Mirrored verbatim by conscious decision (same pattern as PyPsiModifier):
 * only PLATFORM refactoring APIs are used, so Rename / Safe Delete / Move
 * work in PyCharm for free.
 *
 * Threading contract: processors run on the EDT WITHOUT an enclosing read or write
 * action — BaseRefactoringProcessor opens its own command internally (single Undo step).
 * Never call from inside runReadAction.
 */
class PyRefactoringExecutor(private val project: Project) {

    /** Renames [element] and updates all references project-wide. */
    fun rename(element: PsiElement, newName: String): Result<Unit, String> {
        if (DumbService.isDumb(project)) {
            return Result.Failure("Rename is unavailable while indexes are being rebuilt - retry in a moment")
        }
        return try {
            runOnEdt { RenameProcessor(project, element, newName, false, false).run() }
            val renamed = runReadAction {
                !element.isValid || (element as? PsiNamedElement)?.name == newName
            }
            if (renamed) Result.Success(Unit)
            else Result.Failure("Rename produced no change - likely cancelled in the conflicts dialog")
        } catch (e: Exception) {
            Result.Failure("Rename failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Deletes [element] only if no live references remain; fails explicitly otherwise. */
    fun safeDelete(element: PsiElement): Result<Unit, String> {
        if (DumbService.isDumb(project)) {
            return Result.Failure("Safe delete is unavailable while indexes are being rebuilt - retry in a moment")
        }
        return try {
            runOnEdt {
                SafeDeleteProcessor.createInstance(project, null, arrayOf(element), true, true).run()
            }
            val deleted = runReadAction { !element.isValid }
            if (deleted) Result.Success(Unit)
            else Result.Failure(
                "Safe delete did not remove the element - live usages were found " +
                        "(or the deletion was cancelled). Check usages and remove the references first."
            )
        } catch (e: Exception) {
            Result.Failure("Safe delete failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Moves [file] into [targetDirectory]; references are updated by the platform. */
    fun moveFile(file: PsiFile, targetDirectory: PsiDirectory): Result<Unit, String> {
        if (DumbService.isDumb(project)) {
            return Result.Failure("Move is unavailable while indexes are being rebuilt - retry in a moment")
        }
        return try {
            runOnEdt {
                MoveFilesOrDirectoriesProcessor(
                    project, arrayOf<PsiElement>(file), targetDirectory,
                    false, true, null, null
                ).run()
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Failure("Move failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeAndWait(action)
    }
}
