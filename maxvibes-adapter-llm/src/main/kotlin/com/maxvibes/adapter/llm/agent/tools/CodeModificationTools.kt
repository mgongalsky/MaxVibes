// maxvibes-adapter-llm/src/main/kotlin/com/maxvibes/adapter/llm/agent/tools/CodeModificationTools.kt
package com.maxvibes.adapter.llm.agent.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.InsertPosition
import com.maxvibes.domain.model.modification.Modification
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tools для модификации Kotlin кода.
 * Используются Koog агентом для генерации модификаций.
 */
@LLMDescription("Tools for modifying Kotlin code elements")
class CodeModificationTools : ToolSet {

    // Список сгенерированных модификаций
    private val _modifications = mutableListOf<Modification>()
    val modifications: List<Modification> get() = _modifications.toList()

    /**
     * Очистить список модификаций
     */
    fun clearModifications() {
        _modifications.clear()
    }

    @Tool
    @LLMDescription(
        """
        Creates a new code element (class, function, property, etc.) inside an existing element.
        Use this when you need to ADD new code to an existing file or class.
        
        Example paths:
        - "file:src/main/kotlin/User.kt" - adds to file level
        - "file:src/main/kotlin/User.kt/class[User]" - adds inside User class
        - "file:src/main/kotlin/User.kt/class[User]/class[Builder]" - adds inside nested Builder class
        """
    )
    fun createElement(
        @LLMDescription("Target path where to add the element (e.g., 'file:src/User.kt/class[User]')")
        targetPath: String,

        @LLMDescription("Kind of element: FUNCTION, PROPERTY, CLASS, INTERFACE, OBJECT, ENUM, CONSTRUCTOR")
        elementKind: String,

        @LLMDescription("Complete Kotlin code for the new element. Must be valid, compilable Kotlin code.")
        content: String,

        @LLMDescription("Where to insert: FIRST_CHILD, LAST_CHILD, BEFORE, AFTER. Default is LAST_CHILD.")
        position: String = "LAST_CHILD"
    ): String {
        return try {
            val kind = ElementKind.valueOf(elementKind.uppercase())
            val insertPosition = InsertPosition.valueOf(position.uppercase())

            val modification = Modification.CreateElement(
                targetPath = ElementPath(targetPath),
                elementKind = kind,
                content = content.trim(),
                position = insertPosition
            )
            _modifications.add(modification)

            "SUCCESS: Scheduled creation of $elementKind at $targetPath"
        } catch (e: IllegalArgumentException) {
            "ERROR: Invalid elementKind '$elementKind'. Valid values: ${ElementKind.entries.joinToString()}"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        """
        Replaces an existing code element with new content.
        Use this when you need to MODIFY existing code - change function implementation, update property, etc.
        
        The targetPath must point to an existing element that will be completely replaced.
        """
    )
    fun replaceElement(
        @LLMDescription("Path to the element to replace (e.g., 'file:src/User.kt/class[User]/function[toString]')")
        targetPath: String,

        @LLMDescription("Complete new Kotlin code that will replace the existing element. Must be valid Kotlin.")
        newContent: String
    ): String {
        return try {
            val modification = Modification.ReplaceElement(
                targetPath = ElementPath(targetPath),
                newContent = newContent.trim()
            )
            _modifications.add(modification)

            "SUCCESS: Scheduled replacement at $targetPath"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        """
        Deletes an existing code element.
        Use this when you need to REMOVE code - delete a function, property, or class.
        
        Be careful: this permanently removes the element!
        """
    )
    fun deleteElement(
        @LLMDescription("Path to the element to delete (e.g., 'file:src/User.kt/class[User]/function[oldMethod]')")
        targetPath: String
    ): String {
        return try {
            val modification = Modification.DeleteElement(
                targetPath = ElementPath(targetPath)
            )
            _modifications.add(modification)

            "SUCCESS: Scheduled deletion of $targetPath"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        """
        Creates a new Kotlin file with the specified content.
        Use this when you need to create a completely new file.
        """
    )
    fun createFile(
        @LLMDescription("Path for the new file (e.g., 'file:src/main/kotlin/com/example/NewClass.kt')")
        filePath: String,

        @LLMDescription("Complete content of the new file including package declaration and imports")
        content: String
    ): String {
        return try {
            val modification = Modification.CreateFile(
                targetPath = ElementPath(filePath),
                content = content.trim()
            )
            _modifications.add(modification)

            "SUCCESS: Scheduled creation of file $filePath"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        """
        Replaces the entire content of an existing file.
        Use this when you need to completely rewrite a file.
        """
    )
    fun replaceFile(
        @LLMDescription("Path to the file to replace (e.g., 'file:src/main/kotlin/User.kt')")
        filePath: String,

        @LLMDescription("Complete new content for the file")
        newContent: String
    ): String {
        return try {
            val modification = Modification.ReplaceFile(
                targetPath = ElementPath(filePath),
                newContent = newContent.trim()
            )
            _modifications.add(modification)

            "SUCCESS: Scheduled file replacement at $filePath"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        """
        Deletes an entire file.
        Use with caution - this removes the file completely!
        """
    )
    fun deleteFile(
        @LLMDescription("Path to the file to delete (e.g., 'file:src/main/kotlin/OldClass.kt')")
        filePath: String
    ): String {
        return try {
            val modification = Modification.DeleteFile(
                targetPath = ElementPath(filePath)
            )
            _modifications.add(modification)

            "SUCCESS: Scheduled deletion of file $filePath"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        """
        Signals that all modifications are complete.
        Call this when you have finished generating all necessary code changes.
        Returns a summary of all scheduled modifications.
        """
    )
    fun finishModifications(): String {
        val summary = buildString {
            appendLine("=== Modifications Summary ===")
            appendLine("Total modifications: ${_modifications.size}")
            appendLine()

            _modifications.forEachIndexed { index, mod ->
                appendLine("${index + 1}. ${mod.describe()}")
            }
        }
        return summary
    }

    private fun Modification.describe(): String = when (this) {
        is Modification.CreateElement -> "CREATE ${elementKind.name} at ${targetPath.value}"
        is Modification.ReplaceElement -> "REPLACE at ${targetPath.value}"
        is Modification.DeleteElement -> "DELETE at ${targetPath.value}"
        is Modification.CreateFile -> "CREATE FILE ${targetPath.value}"
        is Modification.ReplaceFile -> "REPLACE FILE ${targetPath.value}"
        is Modification.DeleteFile -> "DELETE FILE ${targetPath.value}"
    }
}