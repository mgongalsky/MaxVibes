package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.ContextError
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.domain.model.context.FileNode
import com.maxvibes.domain.model.context.FileTree
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.shared.result.Result

/**
 * Configurable fake ProjectContextPort.
 *
 * Successful operation uses projectContext and fileContents. Setting an error
 * makes the corresponding call return Failure without mutating other state.
 */
class FakeProjectContextPort(
    var projectContext: ProjectContext = defaultContext(),
    val fileContents: MutableMap<String, String> = mutableMapOf()
) : ProjectContextPort {
    var projectContextError: ContextError? = null
    var gatherFilesError: ContextError? = null

    var projectContextCalls: Int = 0
        private set

    val gatheredPathLists = mutableListOf<List<String>>()

    override suspend fun getProjectContext(): Result<ProjectContext, ContextError> {
        projectContextCalls += 1
        val error = projectContextError
        return if (error != null) {
            Result.Failure(error)
        } else {
            Result.Success(projectContext)
        }
    }

    override suspend fun getFileTree(
        maxDepth: Int,
        excludePatterns: List<String>
    ): Result<FileTree, ContextError> =
        Result.Success(projectContext.fileTree)

    override suspend fun gatherFiles(
        paths: List<String>,
        maxTotalSize: Long
    ): Result<GatheredContext, ContextError> {
        gatheredPathLists += paths
        val error = gatherFilesError
        if (error != null) {
            return Result.Failure(error)
        }

        val files = paths.mapNotNull { path ->
            fileContents[path]?.let { content -> path to content }
        }.toMap()
        val tokens = files.values.sumOf {
            GatheredContext.estimateTokens(it)
        }
        return Result.Success(
            GatheredContext(
                files = files,
                totalTokensEstimate = tokens
            )
        )
    }

    override suspend fun findDescriptionFiles(): Result<Map<String, String>, ContextError> =
        Result.Success(emptyMap())

    companion object {
        fun defaultContext() = ProjectContext(
            name = "TestProject",
            rootPath = "C:/test",
            fileTree = FileTree(
                root = FileNode(
                    name = "TestProject",
                    path = "",
                    isDirectory = true
                ),
                totalFiles = 0,
                totalDirectories = 1
            )
        )
    }
}
