// maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/code/ElementPath.kt
package com.maxvibes.domain.model.code

/**
 * Путь к элементу кода.
 * Примеры:
 *   - file:src/main/kotlin/User.kt
 *   - file:src/main/kotlin/User.kt/class[User]
 *   - file:src/main/kotlin/User.kt/class[User]/function[greet]
 */
@JvmInline
value class ElementPath(val value: String) {

    val isFile: Boolean
        get() = value.startsWith("file:") && !value.contains("/class[") &&
                !value.contains("/function[") && !value.contains("/property[")

    val isElement: Boolean
        get() = value.contains("[") && value.contains("]")

    val filePath: String
        get() = value
            .removePrefix("file:")
            .substringBefore("/class[")
            .substringBefore("/function[")
            .substringBefore("/property[")
            .substringBefore("/object[")

    val segments: List<PathSegment>
        get() {
            if (!isElement) return emptyList()
            val elementPart = value.substringAfter(filePath).removePrefix("/")
            if (elementPart.isEmpty()) return emptyList()

            return SEGMENT_PATTERN.findAll(elementPart).map { match ->
                PathSegment(
                    kind = match.groupValues[1],
                    name = match.groupValues[2]
                )
            }.toList()
        }

    val parentPath: ElementPath?
        get() {
            val lastSlash = value.lastIndexOf('/')
            return if (lastSlash > 0 && value.substring(lastSlash).contains("[")) {
                ElementPath(value.substring(0, lastSlash))
            } else null
        }

    val name: String
        get() = segments.lastOrNull()?.name ?: filePath.substringAfterLast('/')

    fun child(kind: String, name: String): ElementPath =
        ElementPath("$value/$kind[$name]")

    override fun toString(): String = value

    companion object {
        private val SEGMENT_PATTERN = Regex("""(\w+)\[([^\]]+)]""")

        fun file(path: String): ElementPath = ElementPath("file:$path")

        fun parse(path: String): ElementPath = ElementPath(path)
    }
}

data class PathSegment(
    val kind: String,
    val name: String
)