package com.maxvibes.application.service.approval

import com.maxvibes.application.port.output.ApprovalPolicyPort
import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalMode
import com.maxvibes.domain.model.approval.ApprovalPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalPolicyEditorTest {

    private class FakePolicyPort(
        var stored: ApprovalPolicy = ApprovalPolicy.DEFAULT
    ) : ApprovalPolicyPort {
        var loads: Int = 0
        var saves: Int = 0

        override fun load(): ApprovalPolicy {
            loads++
            return stored
        }

        override fun save(policy: ApprovalPolicy) {
            saves++
            stored = policy
        }
    }

    @Test
    fun `the editor starts from what the storage holds`() {
        val port = FakePolicyPort(
            ApprovalPolicy.DEFAULT.with(AgentActionKind.COMMAND, ApprovalMode.AUTO_ALLOW)
        )

        val sut = ApprovalPolicyEditor(port)

        assertEquals(ApprovalMode.AUTO_ALLOW, sut.modeFor(AgentActionKind.COMMAND))
        assertFalse(sut.isModified())
    }

    @Test
    fun `selecting another mode marks the page as modified`() {
        val sut = ApprovalPolicyEditor(FakePolicyPort())

        sut.select(AgentActionKind.MODIFICATION, ApprovalMode.AUTO_ALLOW)

        assertTrue(sut.isModified())
        assertEquals(ApprovalMode.AUTO_ALLOW, sut.modeFor(AgentActionKind.MODIFICATION))
    }

    @Test
    fun `selecting the mode that is already stored leaves the page unmodified`() {
        val port = FakePolicyPort(
            ApprovalPolicy.DEFAULT.with(AgentActionKind.MODIFICATION, ApprovalMode.AUTO_ALLOW)
        )
        val sut = ApprovalPolicyEditor(port)

        sut.select(AgentActionKind.MODIFICATION, ApprovalMode.AUTO_ALLOW)

        assertFalse(sut.isModified())
    }

    @Test
    fun `going back to the stored value cancels the modification`() {
        val sut = ApprovalPolicyEditor(FakePolicyPort())
        val original = sut.modeFor(AgentActionKind.COMMAND)

        sut.select(AgentActionKind.COMMAND, ApprovalMode.AUTO_ALLOW)
        sut.select(AgentActionKind.COMMAND, original)

        assertFalse(sut.isModified())
    }

    @Test
    fun `apply writes the draft to the storage and clears the modified flag`() {
        val port = FakePolicyPort()
        val sut = ApprovalPolicyEditor(port)
        sut.select(AgentActionKind.COMMAND, ApprovalMode.AUTO_ALLOW)

        sut.apply()

        assertEquals(1, port.saves)
        assertEquals(ApprovalMode.AUTO_ALLOW, port.stored.modeFor(AgentActionKind.COMMAND))
        assertFalse(sut.isModified())
    }

    @Test
    fun `apply persists every edited kind at once`() {
        val port = FakePolicyPort()
        val sut = ApprovalPolicyEditor(port)

        sut.select(AgentActionKind.VIEW_REQUEST, ApprovalMode.ASK)
        sut.select(AgentActionKind.MODIFICATION, ApprovalMode.AUTO_ALLOW)
        sut.select(AgentActionKind.COMMAND, ApprovalMode.AUTO_ALLOW)
        sut.apply()

        assertEquals(ApprovalMode.ASK, port.stored.modeFor(AgentActionKind.VIEW_REQUEST))
        assertEquals(ApprovalMode.AUTO_ALLOW, port.stored.modeFor(AgentActionKind.MODIFICATION))
        assertEquals(ApprovalMode.AUTO_ALLOW, port.stored.modeFor(AgentActionKind.COMMAND))
    }

    @Test
    fun `reset discards the draft and rereads the storage`() {
        val port = FakePolicyPort()
        val sut = ApprovalPolicyEditor(port)
        val original = sut.modeFor(AgentActionKind.MODIFICATION)
        sut.select(AgentActionKind.MODIFICATION, ApprovalMode.AUTO_ALLOW)

        sut.reset()

        assertEquals(original, sut.modeFor(AgentActionKind.MODIFICATION))
        assertFalse(sut.isModified())
        assertEquals(0, port.saves)
        assertEquals(2, port.loads)
    }

    @Test
    fun `reset picks up a policy changed behind the editor`() {
        val port = FakePolicyPort()
        val sut = ApprovalPolicyEditor(port)

        port.stored = ApprovalPolicy.DEFAULT.with(AgentActionKind.COMMAND, ApprovalMode.AUTO_ALLOW)
        sut.reset()

        assertEquals(ApprovalMode.AUTO_ALLOW, sut.modeFor(AgentActionKind.COMMAND))
        assertFalse(sut.isModified())
    }
}
