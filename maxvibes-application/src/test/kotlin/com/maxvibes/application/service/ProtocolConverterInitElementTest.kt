package com.maxvibes.application.service

import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.modification.Modification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProtocolConverterInitElementTest {

    @Test
    fun `explicit init kind maps to create element`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(
                type = "CREATE_ELEMENT",
                path = "file:A.kt/class[A]",
                content = "init { start() }",
                elementKind = "INIT"
            )
        ) as Modification.CreateElement

        assertEquals(ElementKind.INIT, result.elementKind)
    }

    @Test
    fun `missing kind is inferred from init block`() {
        val result = ProtocolConverter.convertModification(
            InteractionModification(
                type = "CREATE_ELEMENT",
                path = "file:A.kt/class[A]",
                content = "init { start() }",
                elementKind = "FILE"
            )
        ) as Modification.CreateElement

        assertEquals(ElementKind.INIT, result.elementKind)
    }
}
