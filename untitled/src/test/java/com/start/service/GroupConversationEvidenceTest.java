package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupConversationEvidenceTest {

    @Test
    void asynchronousImageEvidenceWaitsForTheOwningMessage() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();

        manager.recordEvidenceForMessage("g", "m-1", GroupConversationEvidence.Type.IMAGE_WEB_SEARCH,
                "m-1", 2, "星露谷更新页面", "https://example.test/update", 0.7,
                GroupConversationEvidence.Status.CONFIRMED, true);
        manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-1", "u-1", "看看这张图", true, false, false, true));

        List<GroupConversationEvidence> evidence = manager.evidenceFor("g", "thread-1");
        assertTrue(evidence.stream().anyMatch(item -> item.type() == GroupConversationEvidence.Type.IMAGE_WEB_SEARCH
                && item.imageIndex() == 2
                && item.sourceMessageId().equals("m-1")));
    }

    @Test
    void unverifiedExternalEvidenceIsRetainedButMarkedForSummaryGuard() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();
        manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-1", "u-1", "看图", true, false, false, true));
        manager.recordEvidenceForMessage("g", "m-1",
                GroupConversationEvidence.Type.UNVERIFIED_EXTERNAL_CONTENT, "m-1", 1,
                "没有完成识图", "", 0D, GroupConversationEvidence.Status.UNVERIFIED, true);

        GroupConversationEvidence item = manager.evidenceFor("g", "thread-1").stream()
                .filter(evidence -> evidence.type() == GroupConversationEvidence.Type.UNVERIFIED_EXTERNAL_CONTENT)
                .findFirst().orElseThrow();
        assertEquals(GroupConversationEvidence.Status.UNVERIFIED, item.status());
        assertTrue(item.allowSummary());
    }

    @Test
    void twoImagesInOneMessageKeepSeparateEvidenceIndexes() {
        GroupConversationSessionManager manager = new GroupConversationSessionManager();
        manager.observe(new GroupConversationSession.Observation(
                "g", "thread-1", "m-1", "u-1", "看看两张", true, false, false, true));
        manager.recordEvidenceForMessage("g", "m-1", GroupConversationEvidence.Type.IMAGE_DESCRIPTION,
                "m-1", 1, "同一描述", "", 0.8, GroupConversationEvidence.Status.CONFIRMED, true);
        manager.recordEvidenceForMessage("g", "m-1", GroupConversationEvidence.Type.IMAGE_DESCRIPTION,
                "m-1", 2, "同一描述", "", 0.8, GroupConversationEvidence.Status.CONFIRMED, true);

        assertEquals(3, manager.evidenceFor("g", "thread-1").size());
        assertEquals(2, manager.evidenceFor("g", "thread-1").stream()
                .filter(item -> item.type() == GroupConversationEvidence.Type.IMAGE_DESCRIPTION).count());
    }
}
