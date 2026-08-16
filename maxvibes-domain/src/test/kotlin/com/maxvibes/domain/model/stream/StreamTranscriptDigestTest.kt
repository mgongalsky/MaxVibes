package com.maxvibes.domain.model.stream

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StreamTranscriptDigestTest {

    @Test
    fun `a thousand fragments collapse into one line`() {
        val digest = StreamTranscriptDigest()

        repeat(1000) { digest.delta("text", "msg_1", 3) }

        assertEquals(
            listOf("STREAM_DELTAS type=text id=msg_1 fragments=1000 chars=3000"),
            digest.flush()
        )
    }

    @Test
    fun `streams are reported apart and keep arrival order`() {
        val digest = StreamTranscriptDigest()

        digest.delta("reasoning", "msg_1", 10)
        digest.delta("text", "msg_1", 4)
        digest.delta("text", "msg_2", 6)
        digest.delta("reasoning", "msg_1", 5)

        assertEquals(
            listOf(
                "STREAM_DELTAS type=reasoning id=msg_1 fragments=2 chars=15",
                "STREAM_DELTAS type=text id=msg_1 fragments=1 chars=4",
                "STREAM_DELTAS type=text id=msg_2 fragments=1 chars=6"
            ),
            digest.flush()
        )
    }

    @Test
    fun `skipped envelopes are counted separately and come last`() {
        val digest = StreamTranscriptDigest()

        digest.skipped(195)
        digest.delta("text", "msg_1", 7)
        digest.skipped(238)

        assertEquals(
            listOf(
                "STREAM_DELTAS type=text id=msg_1 fragments=1 chars=7",
                "STREAM_SKIPPED lines=2 chars=433"
            ),
            digest.flush()
        )
    }

    @Test
    fun `flush without data writes nothing`() {
        assertEquals(emptyList(), StreamTranscriptDigest().flush())
    }

    @Test
    fun `flush resets the counters`() {
        val digest = StreamTranscriptDigest()
        digest.delta("text", "msg_1", 3)
        digest.skipped(100)
        digest.flush()

        digest.delta("text", "msg_1", 4)

        assertEquals(
            listOf("STREAM_DELTAS type=text id=msg_1 fragments=1 chars=4"),
            digest.flush()
        )
    }
}
