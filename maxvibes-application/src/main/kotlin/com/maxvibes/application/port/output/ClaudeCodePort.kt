package com.maxvibes.application.port.output

/**
 * Compatibility name for the original Claude Code transport contract.
 * New application code should depend on [AgentCliPort].
 */
@Deprecated("Use AgentCliPort")
typealias ClaudeCodePort = AgentCliPort
