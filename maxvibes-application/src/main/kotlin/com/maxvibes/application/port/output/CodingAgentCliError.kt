package com.maxvibes.application.port.output

// Canonical provider-independent name during migration.
// The legacy ClaudeCodeError class temporarily owns the nested variants because
// Kotlin type aliases do not expose nested classifiers through the alias name.
typealias CodingAgentCliError = ClaudeCodeError
