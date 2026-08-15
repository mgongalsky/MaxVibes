package com.maxvibes.plugin.voice

/** Builds the small terminology set supplied to the transcription model as a prompt. */
object VoiceContextPromptBuilder {
    fun build(projectName: String, glossaryTerms: List<String>): List<String> =
        buildList {
            add(projectName)
            addAll(DEFAULT_TERMS)
            addAll(glossaryTerms)
        }.map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)

    private val DEFAULT_TERMS = listOf(
        "MaxVibes",
        "Kotlin",
        "IntelliJ IDEA",
        "PSI",
        "Codex",
        "Claude Code"
    )
}
