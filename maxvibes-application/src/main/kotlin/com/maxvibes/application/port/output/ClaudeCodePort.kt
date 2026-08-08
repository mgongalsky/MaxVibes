package com.maxvibes.application.port.output

/**
 * Compatibility name for the original Claude Code transport contract.
 *
 * New application code should depend on [AgentCliPort]. Kept temporarily so the
 * existing Claude adapter and characterization tests can migrate incrementally.
 */
typealias ClaudeCodePort = AgentCliPort
