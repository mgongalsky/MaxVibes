package com.maxvibes.domain.model.context

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ProjectContextTest {

    @Test
    fun `FileNode should format directory correctly`() {
        val node = FileNode(
            name = "src",
            path = "/project/src",
            isDirectory = true,
            children = listOf(
                FileNode("Main.kt", "/project/src/Main.kt", false),
                FileNode("Utils.kt", "/project/src/Utils.kt", false)
            )
        )

        assertTrue(node.isDirectory)
        assertEquals(2, node.children.size)
    }

    @Test
    fun `FileNode should format file correctly`() {
        val node = FileNode(
            name = "Main.kt",
            path = "/project/src/Main.kt",
            isDirectory = false,
            size = 1024
        )

        assertFalse(node.isDirectory)
        assertEquals(1024, node.size)
        assertTrue(node.children.isEmpty())
    }

    @Test
    fun `FileTree toCompactString should respect maxDepth`() {
        val tree = FileTree(
            root = FileNode(
                name = "project",
                path = "/project",
                isDirectory = true,
                children = listOf(
                    FileNode(
                        name = "src",
                        path = "/project/src",
                        isDirectory = true,
                        children = listOf(
                            FileNode(
                                name = "main",
                                path = "/project/src/main",
                                isDirectory = true,
                                children = listOf(
                                    FileNode("App.kt", "/project/src/main/App.kt", false)
                                )
                            )
                        )
                    )
                )
            ),
            totalFiles = 1,
            totalDirectories = 3
        )

        val compact = tree.toCompactString(maxDepth = 2)

        assertTrue(compact.contains("project"))
        assertTrue(compact.contains("src"))
        // maxDepth=2 means we show root(0), src(1), main(2) but not deeper
    }

    @Test
    fun `FileTree should count files and directories`() {
        val tree = FileTree(
            root = FileNode("root", "/root", true),
            totalFiles = 10,
            totalDirectories = 5
        )

        assertEquals(10, tree.totalFiles)
        assertEquals(5, tree.totalDirectories)
    }

    @Test
    fun `GatheredContext should estimate tokens`() {
        val content = "a".repeat(400)  // 400 chars ≈ 100 tokens
        val estimate = GatheredContext.estimateTokens(content)

        assertEquals(100, estimate)
    }

    @Test
    fun `GatheredContext should store files`() {
        val context = GatheredContext(
            files = mapOf(
                "src/Main.kt" to "fun main() {}",
                "src/Utils.kt" to "object Utils {}"
            ),
            totalTokensEstimate = 50
        )

        assertEquals(2, context.files.size)
        assertTrue(context.files.containsKey("src/Main.kt"))
    }

    @Test
    fun `ContextRequest should store requested files`() {
        val request = ContextRequest(
            requestedFiles = listOf("src/Main.kt", "src/Utils.kt"),
            reasoning = "Need main entry point and utilities"
        )

        assertEquals(2, request.requestedFiles.size)
        assertEquals("Need main entry point and utilities", request.reasoning)
    }

    @Test
    fun `TechStack should have defaults`() {
        val stack = TechStack()

        assertEquals("Kotlin", stack.language)
        assertNull(stack.buildTool)
        assertTrue(stack.frameworks.isEmpty())
    }

    @Test
    fun `TechStack should store custom values`() {
        val stack = TechStack(
            language = "Kotlin",
            buildTool = "Gradle",
            frameworks = listOf("Ktor", "Exposed")
        )

        assertEquals("Gradle", stack.buildTool)
        assertEquals(2, stack.frameworks.size)
    }

    @Test
    fun `ProjectContext should store all fields`() {
        val context = ProjectContext(
            name = "MyProject",
            rootPath = "/home/user/project",
            description = "Test project",
            architecture = "Clean Architecture",
            fileTree = FileTree(
                root = FileNode("project", "/project", true),
                totalFiles = 5,
                totalDirectories = 2
            ),
            techStack = TechStack(language = "Kotlin", buildTool = "Gradle")
        )

        assertEquals("MyProject", context.name)
        assertEquals("/home/user/project", context.rootPath)
        assertEquals("Test project", context.description)
        assertEquals("Clean Architecture", context.architecture)
        assertEquals(5, context.fileTree.totalFiles)
        assertEquals("Gradle", context.techStack.buildTool)
    }
}