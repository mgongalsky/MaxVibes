package com.maxvibes.domain.model.context

/**
 * Контекст проекта для LLM
 */
data class ProjectContext(
    val name: String,
    val rootPath: String,
    val description: String? = null,
    val architecture: String? = null,
    val fileTree: FileTree,
    val techStack: TechStack = TechStack()
)

/**
 * Дерево файлов проекта
 */
data class FileTree(
    val root: FileNode,
    val totalFiles: Int,
    val totalDirectories: Int
) {
    /**
     * Компактное представление для промпта (экономим токены)
     */
    fun toCompactString(maxDepth: Int = 4): String {
        val sb = StringBuilder()
        root.appendTo(sb, "", maxDepth, 0)
        return sb.toString()
    }
}

/**
 * Узел дерева файлов
 */
data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<FileNode> = emptyList(),
    val size: Long? = null
) {
    internal fun appendTo(sb: StringBuilder, prefix: String, maxDepth: Int, currentDepth: Int) {
        sb.append(prefix)
        sb.append(if (isDirectory) "📁 " else "📄 ")
        sb.append(name)
        sb.appendLine()

        if (isDirectory && currentDepth < maxDepth) {
            val sortedChildren = children.sortedWith(
                compareBy({ !it.isDirectory }, { it.name })
            )
            sortedChildren.forEachIndexed { index, child ->
                val isLast = index == sortedChildren.lastIndex
                val newPrefix = prefix + if (isLast) "    " else "│   "
                val connector = if (isLast) "└── " else "├── "
                child.appendTo(sb, prefix + connector.dropLast(4), maxDepth, currentDepth + 1)
            }
        }
    }
}

/**
 * Технологический стек проекта
 */
data class TechStack(
    val language: String = "Kotlin",
    val buildTool: String? = null,
    val frameworks: List<String> = emptyList()
)

/**
 * Запрос контекста от LLM (какие файлы нужны)
 */
data class ContextRequest(
    val requestedFiles: List<String>,
    val reasoning: String? = null
)

/**
 * Собранный контекст (содержимое файлов)
 */
data class GatheredContext(
    val files: Map<String, String>,  // path -> content
    val totalTokensEstimate: Int
) {
    companion object {
        // Грубая оценка: ~4 символа на токен
        fun estimateTokens(content: String): Int = content.length / 4
    }
}