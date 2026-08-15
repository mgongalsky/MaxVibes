package com.maxvibes.domain.model.code

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElementPathCharacterizationTest {

    @Test
    fun `nested Kotlin element path preserves file and declaration segments`() {
        val path = ElementPath(
            "file:src/main/kotlin/example/Sample.kt/class[Sample]/function[run]"
        )

        assertEquals("src/main/kotlin/example/Sample.kt", path.filePath)
        assertEquals(
            listOf(
                PathSegment("class", "Sample"),
                PathSegment("function", "run")
            ),
            path.segments
        )
        assertTrue(path.isElement)
        assertFalse(path.isFile)
    }

    @Test
    fun `function segment preserves a test name containing spaces`() {
        val path = ElementPath(
            "file:src/test/kotlin/example/SampleTest.kt/class[SampleTest]/function[maps authentication failure]"
        )

        assertEquals("maps authentication failure", path.segments.last().name)
        assertEquals(
            "file:src/test/kotlin/example/SampleTest.kt/class[SampleTest]",
            path.parentPath?.value
        )
    }

    @Test
    fun `bare init segment remains addressable`() {
        val path = ElementPath(
            "file:src/main/kotlin/example/Sample.kt/class[Sample]/init"
        )

        assertEquals(PathSegment("init", "init"), path.segments.last())
        assertEquals("init", path.name)
    }

    @Test
    fun `primary constructor segment remains bracketed`() {
        val path = ElementPath(
            "file:src/main/kotlin/example/Sample.kt/class[Sample]/constructor[primary]"
        )

        assertEquals(PathSegment("constructor", "primary"), path.segments.last())
        assertEquals(
            "file:src/main/kotlin/example/Sample.kt/class[Sample]/constructor[primary]",
            path.value
        )
    }
}