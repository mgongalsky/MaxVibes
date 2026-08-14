package com.maxvibes.plugin.ui

import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.maxvibes.adapter.psi.operation.PsiNavigator
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.interaction.InteractionModification
import java.io.File
import com.intellij.diff.DiffContentFactory

/** Opens a read-only IntelliJ diff for one proposed PSI modification. */
object ModificationDiffPreview {
    fun show(project: Project, modification: InteractionModification) {
        val elementPath = ElementPath(modification.path)
        val before = currentText(project, elementPath, modification)
        val after = proposedText(elementPath, modification)
        val title = "${modification.type} — ${ChatNavigationHelper.formatElementPath(elementPath)}"
        val factory = DiffContentFactory.getInstance()
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                title,
                factory.create(project, before),
                factory.create(project, after),
                "Current",
                "Proposed"
            )
        )
    }

    private fun currentText(
        project: Project,
        path: ElementPath,
        modification: InteractionModification
    ): String = runReadAction {
        when (modification.type.uppercase()) {
            "CREATE_FILE", "CREATE_ELEMENT", "ADD_IMPORT" -> ""
            "REMOVE_IMPORT" -> "import ${
                modification.importPath.ifBlank {
                    modification.content.removePrefix("import ").trim()
                }
            }"

            "RENAME_ELEMENT" -> path.name
            "MOVE_ELEMENT" -> path.filePath
            else -> {
                if (path.isElement) {
                    PsiNavigator(project).findElement(path)?.text.orEmpty()
                } else {
                    val basePath = project.basePath ?: return@runReadAction ""
                    LocalFileSystem.getInstance()
                        .findFileByIoFile(File(basePath, path.filePath))
                        ?.let { String(it.contentsToByteArray(), it.charset) }
                        .orEmpty()
                }
            }
        }
    }

    private fun proposedText(
        path: ElementPath,
        modification: InteractionModification
    ): String = when (modification.type.uppercase()) {
        "DELETE_FILE", "DELETE_ELEMENT", "SAFE_DELETE", "REMOVE_IMPORT" -> ""
        "ADD_IMPORT" -> "import ${
            modification.importPath.ifBlank {
                modification.content.removePrefix("import ").trim()
            }
        }"

        "RENAME_ELEMENT" -> modification.newName
        "MOVE_ELEMENT" -> modification.destination.trimEnd('/') + "/" + path.filePath.substringAfterLast('/')
        else -> modification.content
    }
}
