// maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/code/ElementPath.kt
package com.maxvibes.domain.model.code

/**
 * Путь к элементу кода.
 * Примеры:
 *   - file:src/main/kotlin/User.kt
 *   - file:src/main/kotlin/User.kt/class[User]
 *   - file:src/main/kotlin/User.kt/class[User]/function[greet]
 *   - file:src/main/kotlin/User.kt/class[User]/companion_object
 *   - file:src/main/kotlin/User.kt/class[User]/init
 *   - file:src/main/kotlin/User.kt/class[User]/constructor[primary]
 */
@JvmInline
value class ElementPath(val value: String) {

    val isFile: Boolean
        get() = value.startsWith("file:") && !hasElementSegments()

    val isElement: Boolean
        get() = hasElementSegments()

    val filePath: String
        get() {
            val withoutPrefix = value.removePrefix("file:")
            // Find the first segment marker: xxx[...] or bare keyword like companion_object
            val segmentStart = findFirstSegmentStart(withoutPrefix)
            return if (segmentStart >= 0) {
                withoutPrefix.substring(0, segmentStart).trimEnd('/')
            } else {
                withoutPrefix
            }
        }

    val segments: List<PathSegment>
        get() {
            val filePathStr = filePath
            val elementPart = value.removePrefix("file:").removePrefix(filePathStr).removePrefix("/")
            if (elementPart.isEmpty()) return emptyList()

            return parseSegments(elementPart)
        }

    val parentPath: ElementPath?
        get() {
            val segs = segments
            if (segs.isEmpty()) return null
            // Reconstruct without the last segment
            val filePathStr = filePath
            val parentSegs = segs.dropLast(1)
            return if (parentSegs.isEmpty()) {
                ElementPath("file:$filePathStr")
            } else {
                val segStr = parentSegs.joinToString("/") { it.toPathString() }
                ElementPath("file:$filePathStr/$segStr")
            }
        }

    val name: String
        get() = segments.lastOrNull()?.name ?: filePath.substringAfterLast('/')

    fun child(kind: String, name: String): ElementPath =
        ElementPath("$value/$kind[$name]")

    /**
     * Append a bare segment (no brackets), e.g. companion_object, init
     */
    fun childBare(kind: String): ElementPath =
        ElementPath("$value/$kind")

    override fun toString(): String = value

    // ═══════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════

    private fun hasElementSegments(): Boolean {
        val withoutPrefix = value.removePrefix("file:")
        val filePathStr = filePath
        val remainder = withoutPrefix.removePrefix(filePathStr).removePrefix("/")
        return remainder.isNotEmpty()
    }

    /**
     * Find the index where element segments start in the path.
     * Detects both bracketed (class[Name]) and bare (companion_object, init) segments.
     */
    private fun findFirstSegmentStart(path: String): Int {
        // Look for patterns like /word[, /companion_object, /init, /constructor[
        val regex = Regex("""/(?:class|interface|object|function|fun|property|val|var|enum|enum_entry|companion_object|companion|init|constructor)[\[/]?""")
        val match = regex.find(path) ?: return -1
        return match.range.first
    }

    companion object {
        /** Matches bracketed segments: kind[name] */
        private val BRACKETED_PATTERN = Regex("""(\w+)\[([^\]]+)]""")
        /** Matches bare segments: companion_object, init */
        internal val BARE_KEYWORDS = setOf("companion_object", "companion", "init")

        fun file(path: String): ElementPath = ElementPath("file:$path")

        fun parse(path: String): ElementPath = ElementPath(path)

        /**
         * Parse segment strings like "class[User]", "companion_object", "init"
         */
        internal fun parseSegments(elementPart: String): List<PathSegment> {
            val result = mutableListOf<PathSegment>()
            val parts = elementPart.split("/").filter { it.isNotBlank() }

            for (part in parts) {
                val bracketMatch = BRACKETED_PATTERN.matchEntire(part)
                if (bracketMatch != null) {
                    result.add(PathSegment(
                        kind = bracketMatch.groupValues[1],
                        name = bracketMatch.groupValues[2]
                    ))
                } else if (part in BARE_KEYWORDS) {
                    // Bare keyword segment — name is the kind itself
                    result.add(PathSegment(kind = part, name = part))
                } else {
                    // Unknown segment format — treat as bare
                    result.add(PathSegment(kind = part, name = part))
                }
            }

            return result
        }
    }
}

data class PathSegment(
    val kind: String,
    val name: String
) {
    fun toPathString(): String {
        return if (kind == name || kind in ElementPath.BARE_KEYWORDS) {
            kind // bare segment: companion_object, init
        } else {
            "$kind[$name]" // bracketed: class[User], function[validate]
        }
    }
}