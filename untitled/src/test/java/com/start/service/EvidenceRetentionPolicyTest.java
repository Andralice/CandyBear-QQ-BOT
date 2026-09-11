package com.start.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceRetentionPolicyTest {

    @Test
    void normalHeadroomUsesLongerRawRetention() {
        EvidenceRetentionPolicy policy = EvidenceRetentionPolicy.forUsableBytes(8L * 1024 * 1024 * 1024);

        assertFalse(policy.pressureMode());
        assertEquals(365, policy.rawEvidence().toDays());
        assertEquals(30, policy.imageAndAudio().toDays());
        assertEquals(7, policy.video().toDays());
        assertEquals(3, policy.derivedArtifacts().toDays());
    }

    @Test
    void lowHeadroomUsesPressureRetention() {
        EvidenceRetentionPolicy policy = EvidenceRetentionPolicy.forUsableBytes(512L * 1024 * 1024);

        assertTrue(policy.pressureMode());
        assertEquals(180, policy.rawEvidence().toDays());
        assertEquals(14, policy.imageAndAudio().toDays());
        assertEquals(3, policy.video().toDays());
        assertEquals(2, policy.derivedArtifacts().toDays());
    }

    @Test
    void unavailableFilesystemInfoKeepsConservativeNormalPolicy() {
        EvidenceRetentionPolicy policy = EvidenceRetentionPolicy.forUsableBytes(-1);

        assertFalse(policy.pressureMode());
        assertEquals(-1, policy.usableBytes());
        assertEquals(365, policy.rawEvidence().toDays());
    }
}
