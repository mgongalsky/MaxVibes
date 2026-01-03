package com.maxvibes.shared.result

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResultTest {

    @Test
    fun `Success should return value`() {
        val result: Result<String, String> = Result.Success("hello")

        assertTrue(result.isSuccess())
        assertFalse(result.isFailure())
        assertEquals("hello", result.getOrNull())
    }

    @Test
    fun `Failure should return null for getOrNull`() {
        val result: Result<String, String> = Result.Failure("error")

        assertFalse(result.isSuccess())
        assertTrue(result.isFailure())
        assertNull(result.getOrNull())
    }

    @Test
    fun `map should transform Success value`() {
        val result: Result<Int, String> = Result.Success(5)

        val mapped = result.map { it * 2 }

        assertEquals(10, mapped.getOrNull())
    }

    @Test
    fun `map should not transform Failure`() {
        val result: Result<Int, String> = Result.Failure("error")

        val mapped = result.map { it * 2 }

        assertTrue(mapped.isFailure())
    }

    @Test
    fun `flatMap should chain Success`() {
        val result: Result<Int, String> = Result.Success(5)

        val chained = result.flatMap { value ->
            if (value > 0) Result.Success(value.toString())
            else Result.Failure("negative")
        }

        assertEquals("5", chained.getOrNull())
    }

    @Test
    fun `flatMap should not chain Failure`() {
        val result: Result<Int, String> = Result.Failure("initial error")

        val chained = result.flatMap { value ->
            Result.Success(value.toString())
        }

        assertTrue(chained.isFailure())
    }

    @Test
    fun `getOrElse should return value for Success`() {
        val result: Result<String, String> = Result.Success("hello")

        val value = result.getOrElse { "default" }

        assertEquals("hello", value)
    }

    @Test
    fun `getOrElse should return default for Failure`() {
        val result: Result<String, String> = Result.Failure("error")

        val value = result.getOrElse { "default: $it" }

        assertEquals("default: error", value)
    }

    @Test
    fun `onSuccess should execute action for Success`() {
        val result: Result<String, String> = Result.Success("hello")
        var executed = false

        result.onSuccess { executed = true }

        assertTrue(executed)
    }

    @Test
    fun `onSuccess should not execute action for Failure`() {
        val result: Result<String, String> = Result.Failure("error")
        var executed = false

        result.onSuccess { executed = true }

        assertFalse(executed)
    }

    @Test
    fun `onFailure should execute action for Failure`() {
        val result: Result<String, String> = Result.Failure("error")
        var capturedError: String? = null

        result.onFailure { capturedError = it }

        assertEquals("error", capturedError)
    }

    @Test
    fun `onFailure should not execute action for Success`() {
        val result: Result<String, String> = Result.Success("hello")
        var executed = false

        result.onFailure { executed = true }

        assertFalse(executed)
    }
}