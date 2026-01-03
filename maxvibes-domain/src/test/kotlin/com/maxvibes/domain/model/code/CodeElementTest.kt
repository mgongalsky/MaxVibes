package com.maxvibes.domain.model.code

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CodeElementTest {

    @Test
    fun `CodeFile toCompactString should include package and imports count`() {
        val file = CodeFile(
            path = ElementPath.file("User.kt"),
            name = "User.kt",
            content = "package com.example\n\nclass User",
            packageName = "com.example",
            imports = listOf("java.util.List", "java.util.Map"),
            children = emptyList()
        )

        val compact = file.toCompactString()

        assertTrue(compact.contains("File: User.kt"))
        assertTrue(compact.contains("package com.example"))
        assertTrue(compact.contains("2 imports"))
    }

    @Test
    fun `CodeClass toCompactString should include modifiers and supertypes`() {
        val clazz = CodeClass(
            path = ElementPath("file:User.kt/class[User]"),
            name = "User",
            content = "data class User(val name: String) : Entity",
            kind = ElementKind.CLASS,
            modifiers = setOf("data", "public"),
            superTypes = listOf("Entity", "Serializable"),
            children = emptyList()
        )

        val compact = clazz.toCompactString()

        assertTrue(compact.contains("data"))
        assertTrue(compact.contains("public"))
        assertTrue(compact.contains("class User"))
        assertTrue(compact.contains("Entity"))
        assertTrue(compact.contains("Serializable"))
    }

    @Test
    fun `CodeFunction toCompactString should include parameters and return type`() {
        val function = CodeFunction(
            path = ElementPath("file:User.kt/class[User]/function[greet]"),
            name = "greet",
            content = "fun greet(name: String): String = \"Hello, \$name\"",
            modifiers = setOf("public"),
            parameters = listOf(
                FunctionParameter("name", "String", null),
                FunctionParameter("age", "Int", "0")
            ),
            returnType = "String",
            hasBody = true
        )

        val compact = function.toCompactString()

        assertTrue(compact.contains("fun greet"))
        assertTrue(compact.contains("name: String"))
        assertTrue(compact.contains("age: Int"))
        assertTrue(compact.contains(": String"))
        assertTrue(compact.contains("{ ... }"))
    }

    @Test
    fun `CodeFunction without body should show abstract`() {
        val function = CodeFunction(
            path = ElementPath("file:User.kt/interface[Greeter]/function[greet]"),
            name = "greet",
            content = "fun greet()",
            modifiers = setOf("abstract"),
            parameters = emptyList(),
            returnType = null,
            hasBody = false
        )

        val compact = function.toCompactString()

        assertFalse(compact.contains("{ ... }"))
    }

    @Test
    fun `CodeProperty toCompactString should show val or var`() {
        val valProperty = CodeProperty(
            path = ElementPath("file:User.kt/class[User]/property[name]"),
            name = "name",
            content = "val name: String",
            modifiers = emptySet(),
            type = "String",
            isVar = false,
            hasInitializer = false
        )

        val varProperty = CodeProperty(
            path = ElementPath("file:User.kt/class[User]/property[age]"),
            name = "age",
            content = "var age: Int = 0",
            modifiers = emptySet(),
            type = "Int",
            isVar = true,
            hasInitializer = true
        )

        assertTrue(valProperty.toCompactString().contains("val name"))
        assertTrue(varProperty.toCompactString().contains("var age"))
        assertTrue(varProperty.toCompactString().contains("= ..."))
    }
}