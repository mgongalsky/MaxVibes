package com.maxvibes.plugin.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The same numbers reach us through two schemas - camelCase over the App Server and
 * snake_case in the CLI's own rollout files - so both spellings are pinned here.
 */
class CodexAppServerRateLimitsTest {

    private val parser = CodexAppServerLineParser()

    @Test
    fun `parses the app server camelCase schema`() {
        val line =
            """{"method":"account/rateLimits/updated","params":{"rateLimits":{"limitId":"codex","limitName":null,"primary":{"usedPercent":26,"windowDurationMins":10080,"resetsAt":1787204233},"secondary":null}},"emittedAtMs":1786811905470}"""

        val parsed = parser.parse(line) as CodexAppServerLineParser.Line.RateLimits

        val window = parsed.windows.single()
        assertEquals("primary", window.id)
        assertEquals(26, window.usedPercent)
        assertEquals(10080, window.windowMinutes)
        assertEquals(1787204233L, window.resetsAtEpochSec)
    }

    @Test
    fun `accepts the rollout snake_case schema with a fractional percent`() {
        val line =
            """{"method":"account/rateLimits/updated","params":{"rate_limits":{"primary":{"used_percent":27.4,"window_minutes":10080,"resets_at":1787204233},"secondary":{"used_percent":3.0,"window_minutes":300,"resets_at":1787200000}}}}"""

        val parsed = parser.parse(line) as CodexAppServerLineParser.Line.RateLimits

        assertEquals(listOf("primary", "secondary"), parsed.windows.map { it.id })
        assertEquals(27, parsed.windows[0].usedPercent)
        assertEquals(300, parsed.windows[1].windowMinutes)
    }

    @Test
    fun `reports no windows as ignored instead of an unknown method`() {
        val line =
            """{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":null,"secondary":null}}}"""

        assertEquals(CodexAppServerLineParser.Line.Ignored, parser.parse(line))
    }
}
