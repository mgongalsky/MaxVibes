package com.maxvibes.domain.model.stream

/**
 * Сворачивает поток дельт в счётчики: транскрипт должен расти по объёму новых
 * данных, а не по числу фрагментов, на которые их порезал транспорт.
 *
 * Дельты копятся до ближайшей содержательной строки: она пишется целиком, а
 * перед ней ложится итог по каждому потоку, так что порядок событий в файле
 * сохраняется. Служебные конверты считаются отдельно — они идут вперемешку с
 * дельтами, и сброс на каждом из них вернул бы построчный лог.
 *
 * Не потокобезопасен: оба транспорта читают stdout одной корутиной.
 */
class StreamTranscriptDigest {

    private data class StreamKey(val kind: String, val id: String)

    private class Counter {
        var fragments: Int = 0
        var chars: Int = 0
    }

    private val streams = LinkedHashMap<StreamKey, Counter>()
    private var skippedLines: Int = 0
    private var skippedChars: Int = 0

    fun delta(kind: String, id: String, chars: Int) {
        val counter = streams.getOrPut(StreamKey(kind, id)) { Counter() }
        counter.fragments++
        counter.chars += chars
    }

    fun skipped(chars: Int) {
        skippedLines++
        skippedChars += chars
    }

    fun flush(): List<String> {
        if (streams.isEmpty() && skippedLines == 0) return emptyList()
        val lines = ArrayList<String>(streams.size + 1)
        streams.forEach { (key, counter) ->
            lines += "STREAM_DELTAS type=${key.kind} id=${key.id} " +
                    "fragments=${counter.fragments} chars=${counter.chars}"
        }
        if (skippedLines > 0) {
            lines += "STREAM_SKIPPED lines=$skippedLines chars=$skippedChars"
        }
        streams.clear()
        skippedLines = 0
        skippedChars = 0
        return lines
    }
}
