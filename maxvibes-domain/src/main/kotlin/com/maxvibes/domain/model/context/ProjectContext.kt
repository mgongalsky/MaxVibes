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
    fun toCompactString(maxDepth: Int = Int.MAX_VALUE): String {
        val sb = StringBuilder()
        fun appendNode(node: FileNode, indent: String) {
            sb.append(indent)
            sb.append(if (node.isDirectory) "📁 " else "📄 ")
            sb.appendLine(node.name)
            if (node.isDirectory) {
                node.children.forEach { appendNode(it, "$indent  ") }
            }
        }
        appendNode(root, "")
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