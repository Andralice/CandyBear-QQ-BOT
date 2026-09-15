package com.start.repository;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MediaArtifactRepositoryTest {

    @Test
    void readsJdbcDecimalAsDoubleWithoutClassCast() {
        assertEquals(0.65, MediaArtifactRepository.toDoubleOrNull(new BigDecimal("0.650")));
    }

    @Test
    void keepsNullConfidenceNull() {
        assertNull(MediaArtifactRepository.toDoubleOrNull(null));
    }
}
