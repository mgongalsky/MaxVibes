package com.maxvibes.adapter.psi.context

import com.maxvibes.domain.model.context.FileNode
import com.maxvibes.domain.model.context.FileTree
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Bounded, language-independent listing. Exclusions affect discovery, not explicit file reads. */
class ProjectFileTreeBuilder(
    private val maxEntries: Int = 3_000,
    private val maxVisitedEntries: Int = 20_000
) {
    fun build(root: Path, maxDepth: Int, excludePatterns: List<String>): FileTree {
        require(maxEntries > 0 && maxVisitedEntries > 0)
        require(Files.isDirectory(root)) { "Project directory does not exist: $root" }
        val patterns = excludePatterns.map { pattern ->
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
        }
        var visited = 0
        var emitted = 0
        var totalFiles = 0
        var totalDirectories = 0
        fun notice(path: Path, reason: String) = FileNode(
            name = "… [$reason; request a known file directly with FULL]",
            path = path.toString(), isDirectory = true
        )

        fun excluded(path: Path): Boolean = patterns.any { it.matches(path.fileName.toString()) }
        fun node(path: Path, depth: Int): FileNode? {
            if (Files.isSymbolicLink(path)) return null
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || !isText(path)) return null
                emitted++
                totalFiles++
                return FileNode(path.fileName.toString(), path.toString(), false, size = Files.size(path))
            }
            if (depth > 0 && Files.isRegularFile(path.resolve("pyvenv.cfg"))) return null
            emitted++
            totalDirectories++
            if (depth >= maxDepth.coerceAtLeast(0)) return FileNode(
                path.fileName.toString(), path.toString(), true,
                listOf(notice(path, "depth limit reached"))
            )
            val candidates = mutableListOf<Path>()
            var scanLimited = false
            try {
                Files.newDirectoryStream(path).use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        if (visited >= maxVisitedEntries) {
                            scanLimited = true
                            break
                        }
                        val child = iterator.next()
                        visited++
                        if (!excluded(child)) candidates.add(child)
                    }
                }
            } catch (_: java.io.IOException) {
                return FileNode(
                    path.fileName.toString(), path.toString(), true,
                    listOf(notice(path, "directory could not be read"))
                )
            }
            val children = mutableListOf<FileNode>()
            val ordered = candidates.sortedWith(
                compareBy<Path>(
                    { Files.isDirectory(it, NOFOLLOW_LINKS) }, { it.fileName.toString() }
                ))
            for (child in ordered) {
                if (emitted >= maxEntries) {
                    children += notice(path, "tree entry limit reached")
                    break
                }
                try {
                    node(child, depth + 1)?.let { children += it }
                } catch (_: java.io.IOException) {
                    children += notice(child, "entry could not be read")
                }
            }
            if (scanLimited) children += notice(path, "directory scan limit reached")
            return FileNode(
                path.fileName.toString(), path.toString(), true,
                children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            )
        }

        val rootNode = requireNotNull(node(root, 0))
        return FileTree(rootNode, totalFiles, totalDirectories)
    }

    private fun isText(path: Path): Boolean {
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension in BINARY_EXTENSIONS) return false
        return Files.newInputStream(path).use { input ->
            val prefix = input.readNBytes(4096)
            // UTF-16 BOMs are valid text even though the sample contains zero bytes.
            val utf16 = prefix.size >= 2 && (
                    (prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte()) ||
                            (prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte()))
            utf16 || prefix.none { it == 0.toByte() }
        }
    }

    companion object {
        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "ico", "webp", "bmp", "pdf",
            "zip", "gz", "bz2", "xz", "7z", "rar", "tar", "jar", "war",
            "class", "pyc", "pyo", "exe", "dll", "so", "dylib", "o", "a",
            "woff", "woff2", "ttf", "otf", "mp3", "mp4", "wav", "ogg",
            "sqlite", "sqlite3", "db", "npy", "npz", "parquet", "pkl"
        )
    }
}
