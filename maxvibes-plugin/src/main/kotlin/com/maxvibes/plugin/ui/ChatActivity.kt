package com.maxvibes.plugin.ui

/** Activity of the whole interaction, independent of input availability and status wording. */
enum class ChatActivity(val needsAttention: Boolean) {
    IDLE(false),
    RUNNING(false),
    WAITING(true),
    FINISHED(true),
    ERROR(false)
}
