package com.maxvibes.adapter.llm.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import dev.langchain4j.internal.Json.JsonCodec
import dev.langchain4j.spi.json.JsonCodecFactory
import java.lang.reflect.Type

/**
 * Custom JsonCodecFactory for IntelliJ IDEA plugin environment.
 *
 * Replaces default JacksonJsonCodec which crashes in IntelliJ plugins due to
 * ServiceLoader classloader conflicts with ObjectMapper.findAndRegisterModules().
 *
 * See: https://docs.langchain4j.dev/tutorials/json/
 */
class IntelliJSafeJsonCodecFactory : JsonCodecFactory {

    override fun create(): JsonCodec {
        return IntelliJSafeJsonCodec()
    }
}

class IntelliJSafeJsonCodec : JsonCodec {

    private val objectMapper: ObjectMapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
        // NO findAndRegisterModules() — that's the whole point!
        safeRegisterModules(this)
    }

    private fun safeRegisterModules(mapper: ObjectMapper) {
        val moduleClassNames = listOf(
            "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule",
            "com.fasterxml.jackson.module.paramnames.ParameterNamesModule",
            "com.fasterxml.jackson.module.kotlin.KotlinModule"
        )
        for (className in moduleClassNames) {
            try {
                val clazz = Class.forName(className, true, this::class.java.classLoader)
                val moduleInstance = clazz.getDeclaredConstructor().newInstance()
                val registerMethod = mapper::class.java.getMethod(
                    "registerModule",
                    com.fasterxml.jackson.databind.Module::class.java
                )
                registerMethod.invoke(mapper, moduleInstance)
            } catch (_: Throwable) {
                // Module not available or classloader mismatch — skip
            }
        }
    }

    override fun <T : Any?> fromJson(json: String, type: Class<T>): T {
        try {
            return objectMapper.readValue(json, type)
        } catch (e: JsonProcessingException) {
            throw RuntimeException("Failed to deserialize: ${e.message}", e)
        }
    }

    override fun <T : Any?> fromJson(json: String, type: Type): T {
        try {
            val javaType = objectMapper.typeFactory.constructType(type)
            return objectMapper.readValue(json, javaType)
        } catch (e: JsonProcessingException) {
            throw RuntimeException("Failed to deserialize: ${e.message}", e)
        }
    }

    override fun toJson(obj: Any): String {
        try {
            return objectMapper.writeValueAsString(obj)
        } catch (e: JsonProcessingException) {
            throw RuntimeException("Failed to serialize: ${e.message}", e)
        }
    }

}