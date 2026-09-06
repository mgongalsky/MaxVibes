package com.maxvibes.domain.model.code

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElementPathSegmentBoundaryTest {
    @Test
    fun `keyword prefixes in directories remain part of file path`() {
        val directories = listOf(
            "classes", "interfaces", "objects", "functions", "functional",
            "propertyStore", "values", "variants", "enums", "enum_entries",
            "companion_objects", "companionship", "initialization", "constructors"
        )
        for (directory in directories) {
            val file = "src/$directory/Example.kt"
            val path = ElementPath.file(file)
            assertEquals(file, path.filePath, directory)
            assertTrue(path.isFile, directory)
            assertFalse(path.isElement, directory)
            assertTrue(path.segments.isEmpty(), directory)
        }
    }

    @Test
    fun `nested declarations start after the complete file path`() {
        val file = "src/interfaces/functions/Example.kt"
        val path = ElementPath("file:$file/class[Example]/function[render]")
        assertEquals(file, path.filePath)
        assertEquals(listOf(PathSegment("class", "Example"), PathSegment("function", "render")), path.segments)
        assertEquals(ElementPath("file:$file/class[Example]"), path.parentPath)
        assertTrue(path.isElement)
        assertFalse(path.isFile)
    }

    @Test
    fun `bare companion and init segments remain supported`() {
        val base = "file:src/Example.kt/class[Example]"
        for (keyword in listOf("companion_object", "companion", "init")) {
            val path = ElementPath("$base/$keyword")
            assertEquals("src/Example.kt", path.filePath)
            assertEquals(PathSegment(keyword, keyword), path.segments.last())
            assertEquals(ElementPath(base), path.parentPath)
        }
    }

    @Test
    fun `indexed init and enum entry segments remain supported`() {
        val init = ElementPath("file:src/Example.kt/class[Example]/init[1]")
        assertEquals(PathSegment("init", "1"), init.segments.last())
        val entry = ElementPath("file:src/Example.kt/enum[State]/enum_entry[READY]")
        assertEquals("src/Example.kt", entry.filePath)
        assertEquals(listOf(PathSegment("enum", "State"), PathSegment("enum_entry", "READY")), entry.segments)
    }
}
