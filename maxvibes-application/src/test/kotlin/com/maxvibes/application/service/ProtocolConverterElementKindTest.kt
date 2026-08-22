package com.maxvibes.application.service

import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.modification.Modification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProtocolConverterElementKindTest {

    private fun createElement(content: String, elementKind: String = "FILE") =
        ProtocolConverter.convertModification(
            InteractionModification(
                type = "CREATE_ELEMENT",
                path = "file:A.kt/class[A]",
                content = content,
                elementKind = elementKind
            )
        ) as Modification.CreateElement

    @Test
    fun `annotated function is recognised as a function`() {
        val content = "@Test\nfun `SKIP command delegates`() {\n    val attacker = troops.first()\n}"
        assertEquals(ElementKind.FUNCTION, createElement(content).elementKind)
    }

    @Test
    fun `private property is recognised as a property`() {
        val content = "private val battleScope = kotlinx.coroutines.CoroutineScope(\n    SupervisorJob()\n)"
        assertEquals(ElementKind.PROPERTY, createElement(content).elementKind)
    }

    @Test
    fun `explicit kind wins over inference`() {
        assertEquals(ElementKind.PROPERTY, createElement("fun x() {}", "PROPERTY").elementKind)
    }

    @Test
    fun `keyword inside a doc comment does not mislead inference`() {
        assertEquals(
            ElementKind.FUNCTION,
            createElement("/** Returns the class name. */\nfun render() = 1").elementKind
        )
    }

    @Test
    fun `enum class is recognised before plain class`() {
        assertEquals(ElementKind.ENUM, createElement("enum class Color { RED, GREEN }").elementKind)
    }

    @Test
    fun `unrecognisable create element content is rejected before PSI`() {
        assertNull(
            ProtocolConverter.convertModification(
                InteractionModification(
                    type = "CREATE_ELEMENT",
                    path = "file:A.kt/class[A]",
                    content = "// nothing to declare here",
                    elementKind = "FILE"
                )
            )
        )
    }
}
