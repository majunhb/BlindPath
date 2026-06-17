package com.blindpath.base.common

import org.junit.Test
import org.junit.Assert.*

/**
 * Result 密封类测试
 * 
 * 测试统一结果封装的各种操作
 */
class ResultTest {

    // ============ 基本状态测试 ============

    @Test
    fun `Success should have data`() {
        val result = Result.Success(42)

        assertTrue("Should be success", result.isSuccess)
        assertFalse("Should not be error", result.isError)
        assertFalse("Should not be loading", result.isLoading)
        assertEquals(42, result.data)
    }

    @Test
    fun `Error should have message`() {
        val result = Result.Error(message = "Something went wrong")

        assertFalse("Should not be success", result.isSuccess)
        assertTrue("Should be error", result.isError)
        assertFalse("Should not be loading", result.isLoading)
        assertEquals("Something went wrong", result.message)
    }

    @Test
    fun `Loading should have no data`() {
        val result: Result<Int> = Result.Loading

        assertFalse("Should not be success", result.isSuccess)
        assertFalse("Should not be error", result.isError)
        assertTrue("Should be loading", result.isLoading)
    }

    // ============ getOrNull() 测试 ============

    @Test
    fun `getOrNull should return data for Success`() {
        val result = Result.Success("test")
        assertEquals("test", result.getOrNull())
    }

    @Test
    fun `getOrNull should return null for Error`() {
        val result = Result.Error<String>(message = "error")
        assertNull(result.getOrNull())
    }

    @Test
    fun `getOrNull should return null for Loading`() {
        val result: Result<String> = Result.Loading
        assertNull(result.getOrNull())
    }

    // ============ getOrDefault() 测试 ============

    @Test
    fun `getOrDefault should return data for Success`() {
        val result = Result.Success(10)
        assertEquals(10, result.getOrDefault(0))
    }

    @Test
    fun `getOrDefault should return default for Error`() {
        val result = Result.Error<Int>(message = "error")
        assertEquals(0, result.getOrDefault(0))
    }

    @Test
    fun `getOrDefault should return default for Loading`() {
        val result: Result<Int> = Result.Loading
        assertEquals(5, result.getOrDefault(5))
    }

    // ============ map() 测试 ============

    @Test
    fun `map should transform Success data`() {
        val result = Result.Success(5)
        val mapped = result.map { it * 2 }

        assertTrue("Should remain Success", mapped.isSuccess)
        assertEquals(10, (mapped as Result.Success).data)
    }

    @Test
    fun `map should preserve Error`() {
        val result: Result<Int> = Result.Error(message = "error")
        val mapped = result.map { it * 2 }

        assertTrue("Should remain Error", mapped.isError)
        assertEquals("error", (mapped as Result.Error).message)
    }

    @Test
    fun `map should preserve Loading`() {
        val result: Result<Int> = Result.Loading
        val mapped = result.map { it * 2 }

        assertTrue("Should remain Loading", mapped.isLoading)
    }

    // ============ onSuccess 测试 ============

    @Test
    fun `onSuccess should be called for Success`() {
        val result = Result.Success(10)
        var called = false
        var receivedData: Int? = null

        result.onSuccess { data ->
            called = true
            receivedData = data
        }

        assertTrue("onSuccess should be called", called)
        assertEquals(10, receivedData)
    }

    @Test
    fun `onSuccess should not be called for Error`() {
        val result: Result<Int> = Result.Error(message = "error")
        var called = false

        result.onSuccess { called = true }

        assertFalse("onSuccess should not be called", called)
    }

    @Test
    fun `onSuccess should not be called for Loading`() {
        val result: Result<Int> = Result.Loading
        var called = false

        result.onSuccess { called = true }

        assertFalse("onSuccess should not be called", called)
    }

    // ============ onError 测试 ============

    @Test
    fun `onError should be called for Error`() {
        val result: Result<Int> = Result.Error(code = 404, message = "not found")
        var called = false
        var receivedCode = 0
        var receivedMessage = ""

        result.onError { code, message ->
            called = true
            receivedCode = code
            receivedMessage = message
        }

        assertTrue("onError should be called", called)
        assertEquals(404, receivedCode)
        assertEquals("not found", receivedMessage)
    }

    @Test
    fun `onError should not be called for Success`() {
        val result = Result.Success(10)
        var called = false

        result.onError { _, _ -> called = true }

        assertFalse("onError should not be called", called)
    }

    // ============ 链式调用测试 ============

    @Test
    fun `chaining onSuccess and onError should work correctly`() {
        val success = Result.Success(10)
        val error: Result<Int> = Result.Error(message = "error")

        var successCalled = false
        var errorCalled = false

        success.onSuccess { successCalled = true }
            .onError { _, _ -> errorCalled = true }

        assertTrue("Success should trigger onSuccess", successCalled)
        assertFalse("Success should not trigger onError", errorCalled)

        successCalled = false
        errorCalled = false

        error.onSuccess { successCalled = true }
            .onError { _, _ -> errorCalled = true }

        assertFalse("Error should not trigger onSuccess", successCalled)
        assertTrue("Error should trigger onError", errorCalled)
    }

    // ============ safeApiCall 测试 ============

    @Test
    fun `safeApiCall should return Success for successful call`() = kotlinx.coroutines.runBlocking {
        val result = safeApiCall { 42 }

        assertTrue("Should be Success", result.isSuccess)
        assertEquals(42, (result as Result.Success).data)
    }

    @Test
    fun `safeApiCall should return Error for exception`() = kotlinx.coroutines.runBlocking {
        val result = safeApiCall<Int> { throw RuntimeException("test error") }

        assertTrue("Should be Error", result.isError)
        assertEquals("test error", (result as Result.Error).message)
    }
}
