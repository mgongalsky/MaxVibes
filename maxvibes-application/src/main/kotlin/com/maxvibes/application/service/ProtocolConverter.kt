package com.maxvibes.application.service

import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.InteractionCommand
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification

/**
 * Converts LLM-protocol DTOs into domain objects. Shared by
 * [ClaudeCodeInteractionService] and [ClipboardInteractionService] — the two
 * services previously kept identical private copies of these functions.
 *
 * Pure functions, no state: invalid or unrecognised input maps to null and the
 * entry is skipped by the caller.
 */
object ProtocolConverter {

    /**
     * Converts an LLM-protocol [InteractionModification] into a domain [Modification].
     * Returns null for blank type/path, unknown types, or missing type-specific fields
     * (import fqn, new name, destination).
     */
    fun convertModification(mod: InteractionModification): Modification? {
        if (mod.type.isBlank() || mod.path.isBlank()) return null
        val elementPath = ElementPath(mod.path)
        val elementKind = try {
            ElementKind.valueOf(mod.elementKind.uppercase())
        } catch (_: Exception) {
            ElementKind.FILE
        }
        val position = try {
            InsertPosition.valueOf(mod.position.uppercase())
        } catch (_: Exception) {
            InsertPosition.LAST_CHILD
        }

        return when (mod.type.uppercase()) {
            "CREATE_FILE" -> Modification.CreateFile(targetPath = elementPath, content = mod.content)
            "REPLACE_FILE" -> Modification.ReplaceFile(targetPath = elementPath, newContent = mod.content)
            "DELETE_FILE" -> Modification.DeleteFile(targetPath = elementPath)
            "CREATE_ELEMENT" -> Modification.CreateElement(
                targetPath = elementPath, elementKind = elementKind,
                content = mod.content, position = position
            )

            "REPLACE_ELEMENT" -> Modification.ReplaceElement(targetPath = elementPath, newContent = mod.content)
            "DELETE_ELEMENT" -> Modification.DeleteElement(targetPath = elementPath)
            "ADD_IMPORT" -> {
                val fqn = mod.importPath.ifBlank { mod.content.removePrefix("import ").trim() }
                if (fqn.isBlank()) null else Modification.AddImport(targetPath = elementPath, importPath = fqn)
            }

            "REMOVE_IMPORT" -> {
                val fqn = mod.importPath.ifBlank { mod.content.removePrefix("import ").trim() }
                if (fqn.isBlank()) null else Modification.RemoveImport(targetPath = elementPath, importPath = fqn)
            }

            "RENAME_ELEMENT" -> {
                val newName = mod.newName.trim()
                if (newName.isBlank()) null
                else Modification.RenameElement(targetPath = elementPath, newName = newName)
            }

            "SAFE_DELETE" -> Modification.SafeDelete(targetPath = elementPath)

            "MOVE_ELEMENT" -> {
                val destination = mod.destination.trim()
                if (destination.isBlank()) null
                else Modification.MoveElement(targetPath = elementPath, destination = destination)
            }

            else -> null
        }
    }

    /**
     * Converts an LLM-protocol [InteractionCommand] into a domain [CommandRequest].
     * Skips entries with a blank command line; clamps the timeout to [1, 3600] seconds.
     */
    fun convertCommand(cmd: InteractionCommand): CommandRequest? {
        if (cmd.command.isBlank()) return null
        return CommandRequest(
            command = cmd.command,
            reason = cmd.reason.takeIf { it.isNotBlank() },
            timeoutSec = cmd.timeoutSec.coerceIn(1, 3600)
        )
    }
}
