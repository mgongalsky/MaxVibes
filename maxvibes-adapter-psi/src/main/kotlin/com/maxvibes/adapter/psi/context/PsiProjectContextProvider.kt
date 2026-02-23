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
        // Refresh BEFORE runReadAction
        val projectDir = com.intellij.openapi.application.ApplicationManager.getApplication()
            .runReadAction<VirtualFile?> { project.guessProjectDir() }
            ?: return Result.Failure(ContextError.ProjectNotFound())

        VfsUtil.markDirtyAndRefresh(false, true, true, projectDir)

        return runReadAction {
            val fileTreeResult = buildFileTree(projectDir, Int.MAX_VALUE, ProjectContextPort.DEFAULT_EXCLUDES)
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

        log("[FileTree] Starting buildFileTree from: ${root.path}")
        log("[FileTree] maxDepth: $maxDepth")
        log("[FileTree] excludePatterns: $excludePatterns")

        fun buildNode(file: java.io.File, depth: Int): FileNode? {
            val excluded = shouldExclude(file.name, excludePatterns)
            if (excluded) {
                log("[FileTree] EXCLUDED: ${file.path}")
                return null
            }

            return if (file.isDirectory) {
                totalDirs++
                val allChildren = file.listFiles()
                log("[FileTree] DIR depth=$depth: ${file.path} | children count: ${allChildren?.size ?: -1}")
                allChildren?.forEach { log("[FileTree]   child: ${it.name} isDir=${it.isDirectory}") }

                val children = allChildren
                    ?.mapNotNull { buildNode(it, depth + 1) }
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                    ?: emptyList()

                FileNode(
                    name = file.name,
                    path = file.path,
                    isDirectory = true,
                    children = children
                )
            } else {
                totalFiles++
                log("[FileTree] FILE depth=$depth: ${file.path}")
                FileNode(
                    name = file.name,
                    path = file.path,
                    isDirectory = false,
                    size = file.length()
                )
            }
        }

        val rootFile = java.io.File(root.path)
        log("[FileTree] rootFile exists: ${rootFile.exists()}, isDir: ${rootFile.isDirectory}")

        val rootNode = buildNode(rootFile, 0)
            ?: return Result.Failure(ContextError.ProjectNotFound())

        log("[FileTree] Done: totalFiles=$totalFiles, totalDirs=$totalDirs")
        return Result.Success(
            FileTree(
                root = rootNode,
                totalFiles = totalFiles,
                totalDirectories = totalDirs
            )
        )
    }

    private fun log(message: String) {
        val writer = java.io.OutputStreamWriter(System.out, Charsets.UTF_8)
        writer.write(message)
        writer.write("\n")
        writer.flush()
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