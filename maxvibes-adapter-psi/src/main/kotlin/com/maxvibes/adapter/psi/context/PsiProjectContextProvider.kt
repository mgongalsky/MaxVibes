package com.maxvibes.adapter.psi.context

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.maxvibes.application.port.output.ContextError
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.domain.model.context.*
import com.maxvibes.shared.result.Result
import java.nio.charset.Charset

/**
 * Реализация ProjectContextPort через IntelliJ VFS
 */
class PsiProjectContextProvider(private val project: Project) : ProjectContextPort {

    override suspend fun getProjectContext(): Result<ProjectContext, ContextError> {
        return runReadAction {
            val projectDir = project.guessProjectDir()
                ?: return@runReadAction Result.Failure(ContextError.ProjectNotFound())

            val fileTreeResult = buildFileTree(projectDir, 5, ProjectContextPort.DEFAULT_EXCLUDES)
            if (fileTreeResult is Result.Failure) {
                return@runReadAction fileTreeResult as Result.Failure
            }
            val fileTree = (fileTreeResult as Result.Success).value

            val descFiles = findDescriptionFilesInternal(projectDir)
            val techStack = detectTechStack(projectDir)

            Result.Success(
                ProjectContext(
                    name = project.name,
                    rootPath = projectDir.path,
                    description = descFiles["README.md"] ?: descFiles["README"],
                    architecture = descFiles["ARCHITECTURE.md"] ?: descFiles["docs/ARCHITECTURE.md"],
                    fileTree = fileTree,
                    techStack = techStack
                )
            )
        }
    }

    override suspend fun getFileTree(
        maxDepth: Int,
        excludePatterns: List<String>
    ): Result<FileTree, ContextError> {
        return runReadAction {
            val projectDir = project.guessProjectDir()
                ?: return@runReadAction Result.Failure(ContextError.ProjectNotFound())

            buildFileTree(projectDir, maxDepth, excludePatterns)
        }
    }

    override suspend fun gatherFiles(
        paths: List<String>,
        maxTotalSize: Long
    ): Result<GatheredContext, ContextError> {
        return runReadAction {
            val projectDir = project.guessProjectDir()
                ?: return@runReadAction Result.Failure(ContextError.ProjectNotFound())

            val files = mutableMapOf<String, String>()
            var totalSize = 0L

            for (path in paths) {
                val file = projectDir.findFileByRelativePath(path)
                    ?: projectDir.findFileByRelativePath(path.removePrefix("/"))

                if (file == null || file.isDirectory) {
                    continue  // Skip missing files silently
                }

                if (totalSize + file.length > maxTotalSize) {
                    return@runReadAction Result.Failure(
                        ContextError.SizeLimitExceeded(totalSize + file.length, maxTotalSize)
                    )
                }

                try {
                    val content = VfsUtil.loadText(file)
                    files[path] = content
                    totalSize += file.length
                } catch (e: Exception) {
                    // Skip unreadable files
                    continue
                }
            }

            Result.Success(
                GatheredContext(
                    files = files,
                    totalTokensEstimate = GatheredContext.estimateTokens(files.values.joinToString("\n"))
                )
            )
        }
    }

    override suspend fun findDescriptionFiles(): Result<Map<String, String>, ContextError> {
        return runReadAction {
            val projectDir = project.guessProjectDir()
                ?: return@runReadAction Result.Failure(ContextError.ProjectNotFound())

            Result.Success(findDescriptionFilesInternal(projectDir))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════

    private fun buildFileTree(
        root: VirtualFile,
        maxDepth: Int,
        excludePatterns: List<String>
    ): Result<FileTree, ContextError> {
        var totalFiles = 0
        var totalDirs = 0

        fun buildNode(file: VirtualFile, depth: Int): FileNode? {
            if (shouldExclude(file.name, excludePatterns)) return null

            return if (file.isDirectory) {
                totalDirs++
                val children = if (depth < maxDepth) {
                    file.children
                        .mapNotNull { buildNode(it, depth + 1) }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                } else {
                    emptyList()
                }
                FileNode(
                    name = file.name,
                    path = file.path,
                    isDirectory = true,
                    children = children
                )
            } else {
                totalFiles++
                FileNode(
                    name = file.name,
                    path = file.path,
                    isDirectory = false,
                    size = file.length
                )
            }
        }

        val rootNode = buildNode(root, 0)
            ?: return Result.Failure(ContextError.ProjectNotFound())

        return Result.Success(
            FileTree(
                root = rootNode,
                totalFiles = totalFiles,
                totalDirectories = totalDirs
            )
        )
    }

    private fun shouldExclude(name: String, patterns: List<String>): Boolean {
        return patterns.any { pattern ->
            when {
                pattern.startsWith("*.") -> name.endsWith(pattern.removePrefix("*"))
                else -> name == pattern
            }
        }
    }

    private fun findDescriptionFilesInternal(projectDir: VirtualFile): Map<String, String> {
        val descFiles = mutableMapOf<String, String>()
        val candidates = listOf(
            "README.md",
            "README",
            "ARCHITECTURE.md",
            "docs/ARCHITECTURE.md",
            "PROJECT_CONTEXT.md",
            "docs/README.md"
        )

        for (candidate in candidates) {
            val file = projectDir.findFileByRelativePath(candidate)
            if (file != null && !file.isDirectory) {
                try {
                    descFiles[candidate] = VfsUtil.loadText(file)
                } catch (_: Exception) {
                    // Skip
                }
            }
        }

        return descFiles
    }

    private fun detectTechStack(projectDir: VirtualFile): TechStack {
        val buildTool = when {
            projectDir.findChild("build.gradle.kts") != null -> "Gradle (Kotlin DSL)"
            projectDir.findChild("build.gradle") != null -> "Gradle"
            projectDir.findChild("pom.xml") != null -> "Maven"
            else -> null
        }

        val frameworks = mutableListOf<String>()

        // Check for common frameworks in build files
        val buildFile = projectDir.findChild("build.gradle.kts")
            ?: projectDir.findChild("build.gradle")

        if (buildFile != null) {
            try {
                val content = VfsUtil.loadText(buildFile)
                if (content.contains("intellij")) frameworks.add("IntelliJ Platform")
                if (content.contains("ktor")) frameworks.add("Ktor")
                if (content.contains("spring")) frameworks.add("Spring")
                if (content.contains("langchain4j")) frameworks.add("LangChain4j")
            } catch (_: Exception) {
                // Skip
            }
        }

        return TechStack(
            language = "Kotlin",
            buildTool = buildTool,
            frameworks = frameworks
        )
    }
}