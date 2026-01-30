package com.maxvibes.application.port.output

import com.maxvibes.domain.model.context.*
import com.maxvibes.shared.result.Result

/**
 * Порт для сбора контекста проекта (реализуется PSI адаптером)
 */
interface ProjectContextPort {

    /**
     * Получить полный контекст проекта (метаданные + дерево файлов)
     */
    suspend fun getProjectContext(): Result<ProjectContext, ContextError>

    /**
     * Получить только дерево файлов
     */
    suspend fun getFileTree(
        maxDepth: Int = 5,
        excludePatterns: List<String> = DEFAULT_EXCLUDES
    ): Result<FileTree, ContextError>

    /**
     * Собрать содержимое указанных файлов
     */
    suspend fun gatherFiles(
        paths: List<String>,
        maxTotalSize: Long = 500_000  // ~500KB, ~125K токенов
    ): Result<GatheredContext, ContextError>

    /**
     * Найти файлы описания проекта (README, ARCHITECTURE, etc.)
     */
    suspend fun findDescriptionFiles(): Result<Map<String, String>, ContextError>

    companion object {
        val DEFAULT_EXCLUDES = listOf(
            ".git",
            ".idea",
            ".gradle",
            "build",
            "out",
            "target",
            "node_modules",
            "*.class",
            "*.jar",
            "*.log"
        )
    }
}

sealed class ContextError(val message: String) {
    class ProjectNotFound : ContextError("Project not found")
    class FileReadError(path: String, details: String) : ContextError("Cannot read $path: $details")
    class SizeLimitExceeded(requested: Long, limit: Long) : ContextError("Size limit exceeded: $requested > $limit")
}