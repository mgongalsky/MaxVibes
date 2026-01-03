package com.maxvibes.domain.model.code

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ElementPathTest {

    @Test
    fun `file path should be recognized as file`() {
        val path = ElementPath.file("src/main/kotlin/User.kt")

        assertTrue(path.isFile)
        assertFalse(path.isElement)
        assertEquals("src/main/kotlin/User.kt", path.filePath)
    }

    @Test
    fun `path with class segment should be recognized as element`() {
        val path = ElementPath("file:src/main/kotlin/User.kt/class[User]")

        assertFalse(path.isFile)
        assertTrue(path.isElement)
        assertEquals("src/main/kotlin/User.kt", path.filePath)
    }

    @Test
    fun `segments should be parsed correctly`() {
        val path = ElementPath("file:src/User.kt/class[User]/function[greet]")

        val segments = path.segments

        assertEquals(2, segments.size)
        assertEquals(PathSegment("class", "User"), segments[0])
        assertEquals(PathSegment("function", "greet"), segments[1])
    }

    @Test
    fun `name should return last segment name`() {
        val path = ElementPath("file:src/User.kt/class[User]/function[greet]")

        assertEquals("greet", path.name)
    }

    @Test
    fun `name should return file name for file path`() {
        val path = ElementPath.file("src/main/kotlin/User.kt")

        assertEquals("User.kt", path.name)
    }

    @Test
    fun `child should create correct path`() {
        val parentPath = ElementPath("file:src/User.kt/class[User]")

        val childPath = parentPath.child("function", "greet")

        assertEquals("file:src/User.kt/class[User]/function[greet]", childPath.value)
    }

    @Test
    fun `parentPath should return parent for element`() {
        val path = ElementPath("file:src/User.kt/class[User]/function[greet]")

        val parent = path.parentPath

        assertNotNull(parent)
        assertEquals("file:src/User.kt/class[User]", parent?.value)
    }

    @Test
    fun `parentPath should return null for file path`() {
        val path = ElementPath.file("src/User.kt")

        assertNull(path.parentPath)
    }

    @Test
    fun `complex nested path should parse correctly`() {
        val path = ElementPath("file:src/User.kt/class[User]/object[Companion]/function[create]")

        val segments = path.segments

        assertEquals(3, segments.size)
        assertEquals("class", segments[0].kind)
        assertEquals("User", segments[0].name)
        assertEquals("object", segments[1].kind)
        assertEquals("Companion", segments[1].name)
        assertEquals("function", segments[2].kind)
        assertEquals("create", segments[2].name)
    }

    @Test
    fun `parse should create ElementPath`() {
        val path = ElementPath.parse("file:src/Test.kt")

        assertEquals("file:src/Test.kt", path.value)
    }
}