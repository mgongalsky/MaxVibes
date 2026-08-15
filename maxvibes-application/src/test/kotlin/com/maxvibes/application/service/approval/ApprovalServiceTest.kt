package com.maxvibes.application.service.approval

import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalDecision
import com.maxvibes.domain.model.approval.ApprovalMode
import com.maxvibes.domain.model.approval.ApprovalPolicy
import com.maxvibes.domain.model.approval.ApprovalSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalServiceTest {

    private val session = "s1"

    private fun serviceWith(policy: ApprovalPolicy) = ApprovalService { policy }

    @Test
    fun `truth table over every action kind and mode without the override`() {
        AgentActionKind.values().forEach { kind ->
            val asking = serviceWith(ApprovalPolicy.DEFAULT.with(kind, ApprovalMode.ASK))
            assertEquals(
                ApprovalDecision.Ask,
                asking.decide(session, kind),
                "ASK policy must ask for $kind"
            )

            val allowing = serviceWith(ApprovalPolicy.DEFAULT.with(kind, ApprovalMode.AUTO_ALLOW))
            assertEquals(
                ApprovalDecision.Allow(ApprovalSource.POLICY),
                allowing.decide(session, kind),
                "AUTO_ALLOW policy must allow $kind"
            )
        }
    }

    @Test
    fun `truth table over every action kind and mode with the override on`() {
        AgentActionKind.values().forEach { kind ->
            listOf(ApprovalMode.ASK, ApprovalMode.AUTO_ALLOW).forEach { mode ->
                val sut = serviceWith(ApprovalPolicy.DEFAULT.with(kind, mode))
                sut.setAllowAll(session, true)

                assertEquals(
                    ApprovalDecision.Allow(ApprovalSource.SESSION_OVERRIDE),
                    sut.decide(session, kind),
                    "override must win over $mode for $kind"
                )
            }
        }
    }

    @Test
    fun `default policy asks for modifications and commands but not for views`() {
        val sut = serviceWith(ApprovalPolicy.DEFAULT)

        assertEquals(
            ApprovalDecision.Allow(ApprovalSource.POLICY),
            sut.decide(session, AgentActionKind.VIEW_REQUEST)
        )
        assertEquals(ApprovalDecision.Ask, sut.decide(session, AgentActionKind.MODIFICATION))
        assertEquals(ApprovalDecision.Ask, sut.decide(session, AgentActionKind.COMMAND))
    }

    @Test
    fun `policy change is visible without restarting the turn`() {
        var policy = ApprovalPolicy.DEFAULT.with(AgentActionKind.COMMAND, ApprovalMode.ASK)
        val sut = ApprovalService { policy }

        assertEquals(ApprovalDecision.Ask, sut.decide(session, AgentActionKind.COMMAND))

        policy = policy.with(AgentActionKind.COMMAND, ApprovalMode.AUTO_ALLOW)

        assertEquals(
            ApprovalDecision.Allow(ApprovalSource.POLICY),
            sut.decide(session, AgentActionKind.COMMAND)
        )
    }

    @Test
    fun `override is scoped to its own session`() {
        val sut = serviceWith(ApprovalPolicy.DEFAULT)
        sut.setAllowAll(session, true)

        assertEquals(
            ApprovalDecision.Allow(ApprovalSource.SESSION_OVERRIDE),
            sut.decide(session, AgentActionKind.MODIFICATION)
        )
        assertEquals(ApprovalDecision.Ask, sut.decide("other", AgentActionKind.MODIFICATION))
    }

    @Test
    fun `releasing the toggle restores the policy`() {
        val sut = serviceWith(ApprovalPolicy.DEFAULT)
        sut.setAllowAll(session, true)
        sut.setAllowAll(session, false)

        assertFalse(sut.isAllowAll(session))
        assertEquals(ApprovalDecision.Ask, sut.decide(session, AgentActionKind.MODIFICATION))
    }

    @Test
    fun `clearing a session drops its override`() {
        val sut = serviceWith(ApprovalPolicy.DEFAULT)
        sut.setAllowAll(session, true)
        assertTrue(sut.isAllowAll(session))

        sut.clearSession(session)

        assertFalse(sut.isAllowAll(session))
        assertEquals(ApprovalDecision.Ask, sut.decide(session, AgentActionKind.COMMAND))
    }
}
