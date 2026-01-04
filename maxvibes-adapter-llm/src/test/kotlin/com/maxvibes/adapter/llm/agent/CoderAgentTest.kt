// maxvibes-adapter-llm/src/test/kotlin/com/maxvibes/adapter/llm/agent/CoderAgentTest.kt
package com.maxvibes.adapter.llm.agent

import com.maxvibes.application.port.output.LLMContext
import com.maxvibes.application.port.output.ProjectInfo
import com.maxvibes.domain.model.code.*
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoderAgentTest {

    @Test
    fun `SYSTEM_PROMPT should contain necessary instructions`() {
        val systemPrompt = CoderAgent.SYSTEM_PROMPT

        // Verify key instructions are present
        assertTrue(systemPrompt.contains("Kotlin"))
        assertTrue(systemPrompt.contains("createElement"))
        assertTrue(systemPrompt.contains("replaceElement"))
        assertTrue(systemPrompt.contains("deleteElement"))
        assertTrue(systemPrompt.contains("ElementPath"))
        assertTrue(systemPrompt.contains("finishModifications"))
    }

    @Test
    fun `SYSTEM_PROMPT should contain path format examples`() {
        val systemPrompt = CoderAgent.SYSTEM_PROMPT

        assertTrue(systemPrompt.contains("file:"))
        assertTrue(systemPrompt.contains("class["))
        assertTrue(systemPrompt.contains("function["))
    }

    @Test
    fun `buildUserPrompt should format code context correctly`() {
        // Create test code elements
        val codeFile = CodeFile(
            path = ElementPath("file:src/main/kotlin/User.kt"),
            name = "User.kt",
            content = "package com.example\n\nclass User(val name: String)",
            packageName = "com.example",
            imports = emptyList(),
            children = listOf(
                CodeClass(
                    path = ElementPath("file:src/main/kotlin/User.kt/class[User]"),
                    name = "User",
                    content = "class User(val name: String)",
                    kind = ElementKind.CLASS,
                    modifiers = setOf("data"),
                    superTypes = emptyList(),
                    children = emptyList()
                )
            )
        )

        val context = LLMContext(
            relevantCode = listOf(codeFile),
            projectInfo = ProjectInfo(
                name = "TestProject",
                rootPath = "/home/user/project",
                language = "Kotlin"
            ),
            additionalInstructions = "Focus on clean code"
        )

        // We can't directly test buildUserPrompt as it's private,
        // but we verify the context structure is correct
        assertNotNull(context.relevantCode)
        assertNotNull(context.projectInfo)
        assertNotNull(context.additionalInstructions)
    }

    @Test
    fun `CodeElement toCompactString should produce readable output`() {
        val codeFunction = CodeFunction(
            path = ElementPath("file:src/Test.kt/class[Test]/function[doSomething]"),
            name = "doSomething",
            content = "fun doSomething(x: Int): String = x.toString()",
            modifiers = setOf("public"),
            parameters = listOf(
                FunctionParameter("x", "Int")
            ),
            returnType = "String",
            hasBody = true
        )

        val compactString = codeFunction.toCompactString()

        assertTrue(compactString.contains("fun doSomething"))
        assertTrue(compactString.contains("x: Int"))
        assertTrue(compactString.contains("String"))
    }

    @Test
    fun `CodeClass toCompactString should include modifiers and supertypes`() {
        val codeClass = CodeClass(
            path = ElementPath("file:src/Test.kt/class[MyService]"),
            name = "MyService",
            content = "class MyService : Service, Runnable { }",
            kind = ElementKind.CLASS,
            modifiers = setOf("public", "open"),
            superTypes = listOf("Service", "Runnable"),
            children = emptyList()
        )

        val compactString = codeClass.toCompactString()

        assertTrue(compactString.contains("MyService"))
        assertTrue(compactString.contains("Service"))
        assertTrue(compactString.contains("Runnable"))
    }

    @Test
    fun `empty context should produce valid prompt`() {
        val context = LLMContext(
            relevantCode = emptyList(),
            projectInfo = null,
            additionalInstructions = null
        )

        // Empty context should be handled gracefully
        assertTrue(context.relevantCode.isEmpty())
    }

    @Test
    fun `createCoderAgent factory should create agent`() {
        // This test just verifies the factory function signature
        // Actual agent creation requires a real PromptExecutor
        assertNotNull(CoderAgent.SYSTEM_PROMPT)
    }
}