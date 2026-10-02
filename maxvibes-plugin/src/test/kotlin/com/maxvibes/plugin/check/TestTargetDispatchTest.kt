package com.maxvibes.plugin.check

import com.maxvibes.domain.model.check.TestScopeParser
import com.maxvibes.domain.model.check.TestTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TestTargetDispatchTest {
    @Test
    fun `reported tests scope uses platform target without probing Java`() {
        val target = TestScopeParser.parse("tests").targets.single()
        assertEquals(TestTarget.TestPackage("tests", recursive = true), target)

        val result = TestTargetDispatch.resolve(
            label = target.description,
            platformTargets = { listOf("Python tests directory") },
            javaAvailable = { error("Python discovery must not probe Java") },
            javaTargets = { throw NoClassDefFoundError("com/intellij/psi/JavaPsiFacade") }
        )

        assertEquals(listOf("Python tests directory"), result)
    }

    @Test
    fun `Python target takes precedence when Java is available`() {
        var javaLookups = 0
        val result = TestTargetDispatch.resolve(
            label = "tests",
            platformTargets = { listOf("pytest target") },
            javaAvailable = { true },
            javaTargets = { javaLookups++; listOf("Java package") }
        )

        assertEquals(listOf("pytest target"), result)
        assertEquals(0, javaLookups)
    }

    @Test
    fun `missing Java support rejects package class and method without invoking Java`() {
        for (scope in listOf("tests", "example.ExampleTest", "example.ExampleTest#testExample")) {
            val target = TestScopeParser.parse(scope).targets.single()
            val failure = assertFailsWith<UnsupportedTestTargetException> {
                TestTargetDispatch.resolve<String>(
                    label = target.description,
                    platformTargets = { null },
                    javaAvailable = { false },
                    javaTargets = { throw AssertionError("Java must not be invoked for $scope") }
                )
            }
            assertTrue(failure.message.orEmpty().contains(target.description))
            assertTrue(failure.message.orEmpty().contains("Java PSI is unavailable"))
        }
    }

    @Test
    fun `JVM target still resolves through available Java adapter`() {
        val calls = mutableListOf<String>()
        val result = TestTargetDispatch.resolve(
            label = "example.ExampleTest",
            platformTargets = { calls += "platform"; null },
            javaAvailable = { calls += "availability"; true },
            javaTargets = { calls += "java"; listOf("JUnit class") }
        )

        assertEquals(listOf("JUnit class"), result)
        assertEquals(listOf("platform", "availability", "java"), calls)
    }

    @Test
    fun `Java linkage failure becomes unsupported with its cause preserved`() {
        val cause = NoClassDefFoundError("com/intellij/psi/JavaPsiFacade")
        val failure = assertFailsWith<UnsupportedTestTargetException> {
            TestTargetDispatch.resolve<String>(
                label = "example.ExampleTest",
                platformTargets = { null },
                javaAvailable = { true },
                javaTargets = { throw cause }
            )
        }

        assertSame(cause, failure.cause)
        assertTrue(failure.message.orEmpty().contains("example.ExampleTest"))
        assertTrue(failure.message.orEmpty().contains("JavaPsiFacade"))
    }

    @Test
    fun `unsupported Python target cannot fall through to Java`() {
        val cause = UnsupportedTestTargetException("Python symbol requires a .py file scope")
        val failure = assertFailsWith<UnsupportedTestTargetException> {
            TestTargetDispatch.resolve<String>(
                label = "tests.test_example.ExampleTest",
                platformTargets = { throw cause },
                javaAvailable = { error("Must not probe Java for an unsupported Python target") },
                javaTargets = { error("Must not resolve a Python symbol with Java") }
            )
        }

        assertSame(cause, failure)
    }

    @Test
    fun `empty platform result does not broaden the scope through Java`() {
        val result = TestTargetDispatch.resolve<String>(
            label = "tests",
            platformTargets = { emptyList() },
            javaAvailable = { error("A handled empty scope must not fall through") },
            javaTargets = { error("Must not broaden scope") }
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `missing JVM target remains a lookup miss`() {
        val result = TestTargetDispatch.resolve<String>(
            label = "example.MissingTest",
            platformTargets = { null },
            javaAvailable = { true },
            javaTargets = { emptyList() }
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ordinary Java adapter errors are not disguised as unsupported`() {
        val cause = IllegalStateException("Project model failure")
        val failure = assertFailsWith<IllegalStateException> {
            TestTargetDispatch.resolve<String>(
                label = "example.ExampleTest",
                platformTargets = { null },
                javaAvailable = { true },
                javaTargets = { throw cause }
            )
        }

        assertSame(cause, failure)
    }
}
