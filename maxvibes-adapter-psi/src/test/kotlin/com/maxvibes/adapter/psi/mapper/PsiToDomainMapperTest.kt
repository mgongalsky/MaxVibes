package com.maxvibes.adapter.psi.mapper

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.maxvibes.domain.model.code.*
import org.jetbrains.kotlin.psi.KtFile

class PsiToDomainMapperTest : BasePlatformTestCase() {

    private lateinit var mapper: PsiToDomainMapper

    override fun setUp() {
        super.setUp()
        mapper = PsiToDomainMapper()
    }

    fun `test mapFile should map simple class`() {
        // Given
        val ktFile = myFixture.configureByText(
            "Test.kt",
            """
            package com.example
            
            class User(val name: String)
            """.trimIndent()
        ) as KtFile

        // When
        val result = mapper.mapFile(ktFile, project.basePath ?: "")

        // Then
        assertTrue(result is CodeFile)
        val codeFile = result as CodeFile
        assertEquals("Test.kt", codeFile.name)
        assertEquals("com.example", codeFile.packageName)
        assertEquals(1, codeFile.children.size)

        val child = codeFile.children[0]
        assertTrue(child is CodeClass)
        assertEquals("User", child.name)
        assertEquals(ElementKind.CLASS, child.kind)
    }

    fun `test mapFile should map class with functions`() {
        // Given
        val ktFile = myFixture.configureByText(
            "Service.kt",
            """
            class Service {
                fun doWork(): String {
                    return "done"
                }
                
                fun cleanup() {}
            }
            """.trimIndent()
        ) as KtFile

        // When
        val result = mapper.mapFile(ktFile, project.basePath ?: "") as CodeFile

        // Then
        val serviceClass = result.children[0] as CodeClass
        assertEquals(2, serviceClass.children.size)

        val doWork = serviceClass.children[0] as CodeFunction
        assertEquals("doWork", doWork.name)
        assertEquals("String", doWork.returnType)
        assertTrue(doWork.hasBody)

        val cleanup = serviceClass.children[1] as CodeFunction
        assertEquals("cleanup", cleanup.name)
        assertNull(cleanup.returnType)
    }

    fun `test mapFile should map interface`() {
        // Given
        val ktFile = myFixture.configureByText(
            "Repository.kt",
            """
            interface Repository {
                fun save(item: Any)
                fun find(id: String): Any?
            }
            """.trimIndent()
        ) as KtFile

        // When
        val result = mapper.mapFile(ktFile, project.basePath ?: "") as CodeFile

        // Then
        val repository = result.children[0] as CodeClass
        assertEquals(ElementKind.INTERFACE, repository.kind)
        assertEquals(2, repository.children.size)
    }

    fun `test mapFile should map data class with properties`() {
        // Given
        val ktFile = myFixture.configureByText(
            "Person.kt",
            """
            data class Person(
                val name: String,
                var age: Int = 0
            )
            """.trimIndent()
        ) as KtFile

        // When
        val result = mapper.mapFile(ktFile, project.basePath ?: "") as CodeFile

        // Then
        val person = result.children[0] as CodeClass
        assertTrue(person.modifiers.contains("data"))
    }

    fun `test mapFile should map object`() {
        // Given
        val ktFile = myFixture.configureByText(
            "Singleton.kt",
            """
            object Singleton {
                fun getInstance() = this
            }
            """.trimIndent()
        ) as KtFile

        // When
        val result = mapper.mapFile(ktFile, project.basePath ?: "") as CodeFile

        // Then
        val singleton = result.children[0] as CodeClass
        assertEquals(ElementKind.OBJECT, singleton.kind)
        assertEquals("Singleton", singleton.name)
    }

    fun `test mapFile should extract modifiers`() {
        // Given
        val ktFile = myFixture.configureByText(
            "Abstract.kt",
            """
            abstract class Base {
                protected open fun method() {}
                private val secret = "hidden"
            }
            """.trimIndent()
        ) as KtFile

        // When
        val result = mapper.mapFile(ktFile, project.basePath ?: "") as CodeFile

        // Then
        val base = result.children[0] as CodeClass
        assertTrue(base.modifiers.contains("abstract"))

        val method = base.children[0] as CodeFunction
        assertTrue(method.modifiers.contains("protected"))
        assertTrue(method.modifiers.contains("open"))
    }

    fun `test inferKind should return correct kinds`() {
        // Given
        val ktFile = myFixture.configureByText(
            "Kinds.kt",
            """
            class MyClass
            interface MyInterface
            object MyObject
            enum class MyEnum { A, B }
            """.trimIndent()
        ) as KtFile

        // When/Then
        val declarations = ktFile.declarations
        assertEquals(ElementKind.CLASS, mapper.inferKind(declarations[0]))
        assertEquals(ElementKind.INTERFACE, mapper.inferKind(declarations[1]))
        assertEquals(ElementKind.OBJECT, mapper.inferKind(declarations[2]))
        assertEquals(ElementKind.ENUM, mapper.inferKind(declarations[3]))
    }
}