package com.maxvibes.plugin.settings

import com.maxvibes.domain.model.approval.AgentActionKind
import com.maxvibes.domain.model.approval.ApprovalMode
import com.maxvibes.domain.model.approval.ApprovalPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApprovalPolicySettingsTest {

    private val settings = ApprovalPolicySettings()

    /** Feeds the component a state as if it had just been read from a hand-edited xml. */
    private fun loadFromFile(vararg entries: Pair<String, String>) {
        settings.loadState(ApprovalPolicySettings.State().apply { modes = mutableMapOf(*entries) })
    }

    @Test
    fun `a project that was never configured gets the default policy`() {
        assertEquals(ApprovalPolicy.DEFAULT.asMap(), settings.load().asMap())
    }

    @Test
    fun `a saved policy comes back unchanged`() {
        val policy = ApprovalPolicy.DEFAULT
            .with(AgentActionKind.VIEW_REQUEST, ApprovalMode.AUTO_ALLOW)
            .with(AgentActionKind.COMMAND, ApprovalMode.ASK)

        settings.save(policy)

        assertEquals(policy.asMap(), settings.load().asMap())
    }

    @Test
    fun `the policy survives an IDE restart`() {
        val policy = ApprovalPolicy.DEFAULT.with(AgentActionKind.MODIFICATION, ApprovalMode.AUTO_ALLOW)
        settings.save(policy)

        val afterRestart = ApprovalPolicySettings()
        afterRestart.loadState(settings.state)

        assertEquals(policy.asMap(), afterRestart.load().asMap())
    }

    @Test
    fun `saving writes every action kind explicitly`() {
        settings.save(ApprovalPolicy.DEFAULT)

        assertEquals(AgentActionKind.values().size, settings.state.modes.size)
    }

    @Test
    fun `modes are stored as plain names, so the file stays readable and downgradable`() {
        settings.save(ApprovalPolicy.DEFAULT.with(AgentActionKind.COMMAND, ApprovalMode.AUTO_ALLOW))

        assertEquals(ApprovalMode.AUTO_ALLOW.name, settings.state.modes[AgentActionKind.COMMAND.name])
    }

    @Test
    fun `an action kind the plugin no longer knows is dropped, the rest of the file still applies`() {
        loadFromFile(
            AgentActionKind.MODIFICATION.name to ApprovalMode.AUTO_ALLOW.name,
            "TELEPATHY" to ApprovalMode.AUTO_ALLOW.name
        )

        val policy = settings.load()

        assertEquals(ApprovalMode.AUTO_ALLOW, policy.modeFor(AgentActionKind.MODIFICATION))
        assertEquals(ApprovalMode.ASK, policy.modeFor(AgentActionKind.COMMAND))
    }

    @Test
    fun `an unknown mode name falls back to the default for that kind only`() {
        loadFromFile(
            AgentActionKind.COMMAND.name to "AUTO_DENY",
            AgentActionKind.VIEW_REQUEST.name to ApprovalMode.AUTO_ALLOW.name
        )

        val policy = settings.load()

        assertEquals(ApprovalMode.ASK, policy.modeFor(AgentActionKind.COMMAND))
        assertEquals(ApprovalMode.AUTO_ALLOW, policy.modeFor(AgentActionKind.VIEW_REQUEST))
    }
}
