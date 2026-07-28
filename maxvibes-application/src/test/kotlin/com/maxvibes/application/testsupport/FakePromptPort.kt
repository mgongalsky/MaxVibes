package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.PromptPort
import com.maxvibes.application.port.output.PromptTemplates

/**
 * Fake [PromptPort] with fixed prompt texts.
 */
class FakePromptPort : PromptPort {

    override fun getPrompts(): PromptTemplates = PromptTemplates.EMPTY

    override fun hasCustomPrompts(): Boolean = false

    override fun openOrCreatePrompts() {
        // nothing to open in tests
    }

    override fun claudeCodeSystem(): String = "CLAUDE CODE SYSTEM PROMPT"
}
