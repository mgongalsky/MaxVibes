package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.ContextError
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.domain.model.context.FileNode
import com.maxvibes.domain.model.context.FileTree
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.shared.result.Result

/**
 * Fake [ProjectContextPort] with a fixed project context and a configurable
 * in-memory file store backing [gatherFiles]. Unknown paths are silently
 * skipped, mirroring the lenient behaviour of the PSI adapter.
 */
class FakeProjectContextPort(
    var projectContext: ProjectContext = defaultContext(),
    val fileContents: MutableMap<String, String> = mutableMapOf()
) : ProjectContextPort {

    /** Every path list passed to [gatherFiles], in order. */
    val gatheredPathLists = mutableListOf<List<String>>()

    override suspend fun getProjectContext(): Result<ProjectContext, ContextError> =
        Result.Success(projectContext)

    override suspend fun getFileTree(
        maxDepth: Int,
        excludePatterns: List<String>
    ): Result<FileTree, ContextError> = Result.Success(projectContext.fileTree)

    override suspend fun gatherFiles(
        paths: List<String>,
        maxTotalSize: Long
    ): Result<GatheredContext, ContextError> {
        gatheredPathLists += paths
        val files = paths.mapNotNull { path -> fileContents[path]?.let { path to it } }.toMap()
        val tokens = files.values.sumOf { GatheredContext.estimateTokens(it) }
        return Result.Success(GatheredContext(files, tokens))
    }

    override suspend fun findDescriptionFiles(): Result<Map<String, String>, ContextError> =
        Result.Success(emptyMap())

    companion object {
        fun defaultContext() = ProjectContext(
            name = "TestProject",
            rootPath = "C:/test",
            fileTree = FileTree(
                root = FileNode(name = "TestProject", path = "", isDirectory = true),
                totalFiles = 0,
                totalDirectories = 1
            )
        )
    }
}
