package com.maxvibes.application.service

import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.InteractionCommand
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.check.CheckKind
import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.interaction.InteractionCheck

/**
 * Converts LLM-protocol DTOs into domain objects. Shared by
 * [ClaudeCodeInteractionService] and [ClipboardInteractionService] — the two
 * services previously kept identical private copies of these functions.
 *
 * Pure functions, no state: invalid or unrecognised input maps to null and the
 * entry is skipped by the caller.
 */
object ProtocolConverter {

    fun convertModification(mod: InteractionModification): Modification? {
        if (mod.type.isBlank() || mod.path.isBlank()) return null
        val elementPath = ElementPath(mod.path)
        val parsedElementKind = runCatching {
            ElementKind.valueOf(mod.elementKind.uppercase())
        }.getOrDefault(ElementKind.FILE)
        val position = runCatching {
            InsertPosition.valueOf(mod.position.uppercase())
        }.getOrDefault(InsertPosition.LAST_CHILD)

        return when (mod.type.uppercase()) {
            "CREATE_FILE" -> Modification.CreateFile(targetPath = elementPath, content = mod.content)
            "REPLACE_FILE" -> Modification.ReplaceFile(targetPath = elementPath, newContent = mod.content)
            "DELETE_FILE" -> Modification.DeleteFile(targetPath = elementPath)
            "CREATE_ELEMENT" -> {
                val elementKind = if (parsedElementKind == ElementKind.FILE) {
                    inferElementKind(mod.content)
                } else {
                    parsedElementKind
                }
                if (elementKind == ElementKind.FILE) return null
                Modification.CreateElement(
                    targetPath = elementPath,
                    elementKind = elementKind,
                    content = mod.content,
                    position = position
                )
            }

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
                if (newName.isBlank()) null else Modification.RenameElement(targetPath = elementPath, newName = newName)
            }

            "SAFE_DELETE" -> Modification.SafeDelete(targetPath = elementPath)
            "MOVE_ELEMENT" -> {
                val destination = mod.destination.trim()
                if (destination.isBlank()) null else Modification.MoveElement(
                    targetPath = elementPath,
                    destination = destination
                )
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

    /**
     * Converts an LLM-protocol [InteractionCheck] into a domain [CheckRequest].
     * Skips entries naming an unknown kind; clamps the timeout to [1, 3600] seconds.
     */
    fun convertCheck(check: InteractionCheck): CheckRequest? {
        val kind = try {
            CheckKind.valueOf(check.kind.trim().uppercase())
        } catch (_: Exception) {
            return null
        }
        return CheckRequest(
            kind = kind,
            scope = check.scope?.trim()?.takeIf { it.isNotBlank() },
            reason = check.reason.takeIf { it.isNotBlank() },
            timeoutSec = check.timeoutSec.coerceIn(1, 3600)
        )
    }

    /**
     * Определяет вид объявления по его тексту — для случая, когда модель не прислала
     * `elementKind`.
     *
     * Комментарии и строковые литералы вырезаются: слово `class` из KDoc или из
     * аргумента аннотации иначе перебивает настоящее ключевое слово. Не удалось
     * распознать — возвращается [ElementKind.FILE], то есть прежнее поведение.
     */
    private fun inferElementKind(content: String): ElementKind {
        val code = content
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("""//[^\n]*"""), " ")
            .replace(Regex("\"[^\"]*\""), " ")
        val keyword = Regex("""\b(enum\s+class|fun|val|var|class|interface|object|init|constructor)\b""")
            .find(code)?.groupValues?.get(1)
            ?.replace(Regex("""\s+"""), " ")
        return when (keyword) {
            "fun" -> ElementKind.FUNCTION
            "val", "var" -> ElementKind.PROPERTY
            "enum class" -> ElementKind.ENUM
            "class" -> ElementKind.CLASS
            "interface" -> ElementKind.INTERFACE
            "object" -> ElementKind.OBJECT
            "init" -> ElementKind.INIT
            "constructor" -> ElementKind.CONSTRUCTOR
            else -> ElementKind.FILE
        }
    }
}
