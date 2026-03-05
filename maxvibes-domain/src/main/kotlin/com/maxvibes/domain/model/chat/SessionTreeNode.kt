package com.maxvibes.domain.model.chat

data class SessionTreeNode(
    val session: ChatSession,
    val children: List<SessionTreeNode> = emptyList()
) {
    val id: String get() = session.id
    val title: String get() = session.title
    val depth: Int get() = session.depth
    val hasChildren: Boolean get() = children.isNotEmpty()

    fun withChildren(newChildren: List<SessionTreeNode>): SessionTreeNode =
        copy(children = newChildren)
}
