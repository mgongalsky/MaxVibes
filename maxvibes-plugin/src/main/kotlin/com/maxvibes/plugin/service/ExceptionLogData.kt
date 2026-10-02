package com.maxvibes.plugin.service

/** Keeps causes and suppressed exceptions using the standard, cycle-safe renderer. */
internal fun exceptionLogData(
    ex: Throwable?,
    base: Map<String, Any?>?,
    stack: Boolean = true
): Map<String, Any?>? {
    if (ex == null) return base
    val details = mutableMapOf<String, Any?>(
        "ex" to ex.javaClass.simpleName,
        "exMsg" to ex.message
    )
    if (stack) details["stack"] = ex.stackTraceToString()
    return (base ?: emptyMap()) + details
}
