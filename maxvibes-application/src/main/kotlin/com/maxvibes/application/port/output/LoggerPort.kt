package com.maxvibes.application.port.output

interface LoggerPort {
    fun debug(tag: String, msg: String, data: Map<String, Any?>? = null)
    fun info(tag: String, msg: String, data: Map<String, Any?>? = null)
    fun warn(tag: String, msg: String, ex: Throwable? = null, data: Map<String, Any?>? = null)
    fun error(tag: String, msg: String, ex: Throwable? = null, data: Map<String, Any?>? = null)
}
