package com.start.service;

import com.start.model.ProfileFactSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroReviewServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void payloadCarriesFullMessageAnchorsAndPreviousFacts() throws Exception {
        String payload = MacroReviewService.buildPayload(List.of(
                Map.of("id", 101L, "user_id", "u1", "sender_type", "USER",
                        "is_robot_reply", false, "created_at", "2026-09-20 12:00:00",
                        "reply_to_id", 0L, "content", "我在上班", "availability_status", "AVAILABLE")
        ), Map.of("u1", List.of(new ProfileFactSummary(7, "interest", "SELF_REPORTED",
                "喜欢文明6", "TEMPORARY", 0.8, "PERSONAL",
                LocalDateTime.of(2026, 9, 19, 12, 0), null))));

        var root = MAPPER.readTree(payload);
        assertEquals("GROUP_PUBLIC", root.path("scope").asText());
        assertEquals("101", root.path("messages").get(0).path("message_id").asText());
        assertEquals("我在上班", root.path("messages").get(0).path("text").asText());
        assertEquals("喜欢文明6", root.path("previous_facts").path("u1").get(0).path("claim").asText());
    }

    @Test
    void validationRejectsRobotAndWrongSenderEvidence() throws Exception {
        List<Map<String, Object>> window = List.of(
                row(101L, "u1", "USER", false, "AVAILABLE"),
                row(102L, "bot", "BOT", true, "AVAILABLE"),
                row(103L, "u2", "USER", false, "AVAILABLE"));
        var modelResult = MacroReviewService.parseJsonObject("""
                {"observations":[
                  {"subject_user_id":"u1","relation_target":"SELF","predicate":"job",
                   "value":"在上班","temporal_scope":"CURRENT_STATE","speech_act":"SELF_ASSERTION",
                   "status":"CANDIDATE","confidence":0.9,"evidence_message_ids":[102],"reason":"机器人说的"},
                  {"subject_user_id":"u1","relation_target":"SELF","predicate":"interest",
                   "value":"喜欢游戏","temporal_scope":"ONE_OFF","speech_act":"SELF_ASSERTION",
                   "status":"CANDIDATE","confidence":0.8,"evidence_message_ids":[103],"reason":"他人消息"}
                ],"stories":[],"group_state":[]}
                """);

        var validated = EvidenceAnalysisWorker.validateMacroResult(modelResult, window);

        assertEquals(0, validated.path("validation").path("valid_observations").asInt());
        assertEquals(2, validated.path("validation").path("invalid_observations").asInt());
        assertTrue(validated.path("validation").path("private_messages_excluded").asBoolean());
        assertEquals("NONE", validated.path("observations").get(0).path("write_action").asText());
        assertTrue(validated.path("observations").get(0).path("validation_errors").size() > 0);
    }

    @Test
    void parsesJsonFencesWithoutAcceptingNonObjectOutput() throws Exception {
        var parsed = MacroReviewService.parseJsonObject("```json\n{\"observations\":[]}\n```");
        assertTrue(parsed.isObject());
        assertEquals(0, parsed.path("observations").size());
    }

    private static Map<String, Object> row(long id, String userId, String senderType,
                                           boolean robot, String availability) {
        return Map.of("id", id, "user_id", userId, "sender_type", senderType,
                "is_robot_reply", robot, "availability_status", availability);
    }
}
