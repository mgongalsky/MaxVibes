package com.maxvibes.application.service

import com.maxvibes.domain.model.check.CheckKind
import com.maxvibes.domain.model.interaction.InteractionCheck
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolConverterCheckTest {

    @Test
    fun `kind is case-insensitive`() {
        val converted = ProtocolConverter.convertCheck(InteractionCheck(kind = " build "))

        assertEquals(CheckKind.BUILD, converted?.kind)
    }

    @Test
    fun `unknown kind drops the entry instead of failing the whole response`() {
        assertNull(ProtocolConverter.convertCheck(InteractionCheck(kind = "LINT")))
        assertNull(ProtocolConverter.convertCheck(InteractionCheck(kind = "")))
    }

    @Test
    fun `blank scope and reason collapse to null`() {
        val converted = ProtocolConverter.convertCheck(
            InteractionCheck(kind = "TESTS", scope = "   ", reason = "")
        )

        assertNull(converted?.scope)
        assertNull(converted?.reason)
    }

    @Test
    fun `scope is trimmed`() {
        val converted = ProtocolConverter.convertCheck(
            InteractionCheck(kind = "TESTS", scope = "  com.example.OrderTest ")
        )

        assertEquals("com.example.OrderTest", converted?.scope)
    }

    @Test
    fun `timeout is clamped to the allowed range`() {
        assertEquals(1, ProtocolConverter.convertCheck(InteractionCheck("BUILD", timeoutSec = 0))?.timeoutSec)
        assertEquals(3600, ProtocolConverter.convertCheck(InteractionCheck("BUILD", timeoutSec = 99_999))?.timeoutSec)
        assertEquals(600, ProtocolConverter.convertCheck(InteractionCheck("BUILD"))?.timeoutSec)
    }
}
