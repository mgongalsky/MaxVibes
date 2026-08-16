package com.maxvibes.domain.model.check

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestScopeParserTest {

    @Test
    fun `an absent scope means the whole project`() {
        val scope = TestScopeParser.parse(null)

        assertTrue(scope.isAll)
        assertEquals(listOf(TestTarget.AllTests), scope.targets)
    }

    @Test
    fun `the aliases for everything are recognised regardless of case`() {
        listOf("all", "*", "**", "All Tests", "PROJECT", "  ").forEach { raw ->
            assertTrue(TestScopeParser.parse(raw).isAll, "expected '$raw' to mean all tests")
        }
    }

    @Test
    fun `a capitalised last segment is a class, a lowercase one is a package`() {
        assertEquals(
            listOf(TestTarget.TestClass("com.foo.BarTest")),
            TestScopeParser.parse("com.foo.BarTest").targets
        )
        assertEquals(
            listOf(TestTarget.TestPackage("com.foo.bar", recursive = true)),
            TestScopeParser.parse("com.foo.bar").targets
        )
    }

    @Test
    fun `the star suffix decides whether subpackages are included`() {
        assertEquals(
            listOf(TestTarget.TestPackage("com.foo.bar", recursive = true)),
            TestScopeParser.parse("com.foo.bar.**").targets
        )
        assertEquals(
            listOf(TestTarget.TestPackage("com.foo.bar", recursive = false)),
            TestScopeParser.parse("com.foo.bar.*").targets
        )
    }

    @Test
    fun `a hash separates the class from a single method`() {
        assertEquals(
            listOf(TestTarget.TestMethod("com.foo.BarTest", "renders the header")),
            TestScopeParser.parse("com.foo.BarTest#renders the header").targets
        )
    }

    @Test
    fun `a hash without a method name is not a target`() {
        assertEquals(listOf(TestTarget.Unknown("com.foo.BarTest#")), TestScopeParser.parse("com.foo.BarTest#").targets)
    }

    @Test
    fun `paths are recognised and normalised to forward slashes`() {
        assertEquals(
            listOf(TestTarget.TestFile("src/test/kotlin/com/foo/BarTest.kt")),
            TestScopeParser.parse("src\\test\\kotlin\\com\\foo\\BarTest.kt").targets
        )
    }

    @Test
    fun `a bare file name still counts as a file`() {
        assertEquals(listOf(TestTarget.TestFile("BarTest.kt")), TestScopeParser.parse("BarTest.kt").targets)
    }

    @Test
    fun `several targets can be listed at once`() {
        val scope = TestScopeParser.parse("com.foo.BarTest, com.foo.baz.*\ncom.foo.OtherTest")

        assertEquals(
            listOf(
                TestTarget.TestClass("com.foo.BarTest"),
                TestTarget.TestPackage("com.foo.baz", recursive = false),
                TestTarget.TestClass("com.foo.OtherTest")
            ),
            scope.targets
        )
    }

    @Test
    fun `an unparseable token becomes Unknown instead of silently running something else`() {
        val scope = TestScopeParser.parse("com.foo.BarTest, run the fast ones!")

        assertEquals(listOf(TestTarget.Unknown("run the fast ones!")), scope.unknownTargets)
        assertTrue(!scope.isAll)
    }

    @Test
    fun `the description names the circle of tests for the bubble`() {
        assertEquals("all tests", TestScopeParser.parse(null).description)
        assertEquals("BarTest", TestScopeParser.parse("com.foo.BarTest").description)
        assertEquals(
            "BarTest, com.foo.baz.**",
            TestScopeParser.parse("com.foo.BarTest, com.foo.baz").description
        )
    }
}
