package com.maxvibes.application.port.output

/**
 * Provider-neutral application name for Agent CLI transport failures.
 *
 * The underlying hierarchy temporarily remains [ClaudeCodeError] so the existing
 * Claude vertical slice can migrate incrementally without breaking nested-type usages.
 */
typealias AgentCliError = ClaudeCodeError
