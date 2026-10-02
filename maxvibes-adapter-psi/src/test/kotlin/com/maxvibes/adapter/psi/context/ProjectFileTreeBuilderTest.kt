package com.maxvibes.adapter.psi.context

import com.maxvibes.application.port.output.ProjectContextPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProjectFileTreeBuilderTest {
    @TempDir
    lateinit var root: Path

    private fun file(path: String, content: String = "text") {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun tree(builder: ProjectFileTreeBuilder = ProjectFileTreeBuilder(), depth: Int = 30) =
        builder.build(root, depth, ProjectContextPort.DEFAULT_EXCLUDES)

    @Test
    fun `includes configuration documentation scripts and tests`() {
        val paths = listOf(
            "pytest-unit.ini", "config.json", "pyproject.toml", ".gitignore",
            "README.md", "layout.xml", "build.gradle.kts", "gradlew", "src/test/ExampleTest.kt"
        )
        paths.forEach { file(it) }
        val listing = tree().toCompactString()
        paths.forEach { assertContains(listing, it.substringAfterLast('/')) }
        assertEquals(paths.size, tree().totalFiles)
    }

    @Test
    fun `excludes service data environments caches and binary files`() {
        listOf(
            ".maxvibes/report.md", ".idea/workspace.xml", ".venv/library.py",
            "node_modules/library.js", "build/output.txt", "__pycache__/cache.pyc",
            "custom-environment/pyvenv.cfg", "custom-environment/library.py"
        ).forEach { file(it) }
        Files.write(root.resolve("unknown.data"), byteArrayOf(1, 0, 2))
        file("picture.png")
        file("src/main.kt")
        val listing = tree().toCompactString()
        assertEquals(1, tree().totalFiles)
        assertContains(listing, "main.kt")
        assertFalse(listing.contains("custom-environment"))
        assertFalse(listing.contains(".maxvibes"))
    }

    @Test
    fun `a directory with over one hundred files remains visible`() {
        repeat(101) { file("src/File$it.kt") }
        assertEquals(101, tree().totalFiles)
    }

    @Test
    fun `entry and scan limits are explicit`() {
        repeat(10) { file("File$it.kt") }
        val entryLimited = tree(ProjectFileTreeBuilder(maxEntries = 3))
        assertEquals(2, entryLimited.totalFiles)
        assertContains(entryLimited.toCompactString(), "tree entry limit reached")
        val scanLimited = tree(ProjectFileTreeBuilder(maxVisitedEntries = 2))
        assertEquals(2, scanLimited.totalFiles)
        assertContains(scanLimited.toCompactString(), "directory scan limit reached")
    }

    @Test
    fun `depth limits are enforced during traversal and rendering`() {
        file("src/main/App.kt")
        val traversal = tree(depth = 1).toCompactString()
        assertContains(traversal, "src")
        assertContains(traversal, "depth limit reached")
        assertFalse(traversal.contains("App.kt"))
        val rendered = tree().toCompactString(maxDepth = 1)
        assertContains(rendered, "depth limit reached")
        assertFalse(rendered.contains("App.kt"))
    }

    @Test
    fun `UTF16 text with a BOM remains discoverable`() {
        Files.write(root.resolve("notes.txt"), "hello".toByteArray(Charsets.UTF_16))
        assertContains(tree().toCompactString(), "notes.txt")
    }
}
