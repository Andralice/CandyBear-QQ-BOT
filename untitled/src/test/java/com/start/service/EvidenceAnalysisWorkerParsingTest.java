package com.start.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvidenceAnalysisWorkerParsingTest {

    @Test
    void decodesUtf8TextForFileEvidence() {
        assertEquals("用户文件内容\n第二行", EvidenceAnalysisWorker.decodeText(
                "用户文件内容\n第二行".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsBinaryBytesAsFileEvidence() {
        assertNull(EvidenceAnalysisWorker.decodeText(new byte[]{0, 1, 2, 3, 4, 5, 0, 0}));
    }
}
