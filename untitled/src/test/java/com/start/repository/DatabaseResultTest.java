package com.start.repository;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BaseRepository.DatabaseResult 单元测试。
 */
class DatabaseResultTest {

    @Test
    void successResult() {
        BaseRepository.DatabaseResult<String> result = BaseRepository.DatabaseResult.success("ok");
        assertTrue(result.isSuccess());
        assertEquals("ok", result.getData());
        assertNull(result.getError());
    }

    @Test
    void failureResult() {
        BaseRepository.DatabaseResult<String> result = BaseRepository.DatabaseResult.failure("timeout");
        assertFalse(result.isSuccess());
        assertNull(result.getData());
        assertEquals("timeout", result.getError());
    }

    @Test
    void successWithNullData() {
        BaseRepository.DatabaseResult<Void> result = BaseRepository.DatabaseResult.success(null);
        assertTrue(result.isSuccess());
        assertNull(result.getData());
        assertNull(result.getError());
    }

    @Test
    void failureWithNullMessage() {
        BaseRepository.DatabaseResult<Integer> result = BaseRepository.DatabaseResult.failure(null);
        assertFalse(result.isSuccess());
        assertNull(result.getError());
    }

    @Test
    void successWithInteger() {
        BaseRepository.DatabaseResult<Long> result = BaseRepository.DatabaseResult.success(42L);
        assertTrue(result.isSuccess());
        assertEquals(42L, result.getData());
    }
}
