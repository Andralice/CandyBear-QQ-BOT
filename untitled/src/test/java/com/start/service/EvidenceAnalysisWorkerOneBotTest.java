package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.MacroReviewMode;
import com.start.model.AnalysisTask;
import com.start.model.MediaPartInput;
import com.start.repository.AnalysisTaskRepository;
import com.start.repository.BaseRepository;
import com.start.repository.MediaArtifactRepository;
import com.start.repository.MessageRepository;
import com.start.repository.ProfileFactRepository;
import com.start.model.ProfileFactCandidateInput;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAnalysisWorkerOneBotTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesTextFromSuccessfulGetFileResponse() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository(task("PARSE_FILE", "{\"part_id\":1}"));
        FakeArtifactRepository artifacts = new FakeArtifactRepository(filePart("file-1"));
        List<String> actions = new ArrayList<>();
        BiFunction<String, JsonNode, CompletableFuture<JsonNode>> api = (action, params) -> {
            actions.add(action + ":" + params.path("file_id").asText());
            ObjectNode response = MAPPER.createObjectNode();
            response.put("status", "ok");
            response.putObject("data").put("base64", Base64.getEncoder().encodeToString(
                    "文件证据\n第二行".getBytes(StandardCharsets.UTF_8)));
            return CompletableFuture.completedFuture(response);
        };

        EvidenceAnalysisWorker worker = worker(tasks, artifacts, api);

        assertTrue(worker.runOnce());
        assertEquals(List.of("get_file:file-1"), actions);
        assertEquals("SUCCEEDED", artifacts.last().status());
        assertTrue(artifacts.last().contentJson().contains("文件证据\\n第二行"));
        assertTrue(tasks.completedResult().contains("\"status\":\"SUCCEEDED\""));
    }

    @Test
    void marksGetFileFailureUnavailableWithoutInventingText() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository(task("PARSE_FILE", "{\"part_id\":1}"));
        FakeArtifactRepository artifacts = new FakeArtifactRepository(filePart("file-failed"));
        BiFunction<String, JsonNode, CompletableFuture<JsonNode>> api = (action, params) -> {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("status", "failed");
            response.putObject("data");
            return CompletableFuture.completedFuture(response);
        };

        EvidenceAnalysisWorker worker = worker(tasks, artifacts, api);

        assertTrue(worker.runOnce());
        assertEquals("UNAVAILABLE", artifacts.last().status());
        assertEquals("FILE_FETCH_FAILED", artifacts.last().errorCode());
        assertTrue(artifacts.last().contentJson() == null);
        assertTrue(tasks.completedResult().contains("FILE_FETCH_FAILED"));
    }

    @Test
    void rejectsOversizedGetFileResponseWithExplicitReason() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository(task("PARSE_FILE", "{\"part_id\":1}"));
        FakeArtifactRepository artifacts = new FakeArtifactRepository(filePart("file-large"));
        BiFunction<String, JsonNode, CompletableFuture<JsonNode>> api = (action, params) -> {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("status", "ok");
            response.putObject("data").put("base64", "A".repeat(4_000_001));
            return CompletableFuture.completedFuture(response);
        };

        EvidenceAnalysisWorker worker = worker(tasks, artifacts, api);

        assertTrue(worker.runOnce());
        assertEquals("UNAVAILABLE", artifacts.last().status());
        assertEquals("FILE_TOO_LARGE", artifacts.last().errorCode());
        assertTrue(artifacts.last().contentJson() == null);
    }

    @Test
    void expandsForwardNodesFromGetForwardMsgResponse() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository(task("EXPAND_FORWARD", "{\"part_id\":1}"));
        FakeArtifactRepository artifacts = new FakeArtifactRepository(forwardPart("forward-1"));
        List<String> actions = new ArrayList<>();
        BiFunction<String, JsonNode, CompletableFuture<JsonNode>> api = (action, params) -> {
            actions.add(action + ":" + params.path("message_id").asText());
            ObjectNode response = MAPPER.createObjectNode();
            response.put("status", "ok");
            var messages = response.putObject("data").putArray("messages");
            messages.addObject().put("message_id", "node-1").put("sender_id", "u-1").put("content", "你好");
            messages.addObject().put("message_id", "node-2").put("sender_id", "u-2").put("content", "世界");
            return CompletableFuture.completedFuture(response);
        };

        EvidenceAnalysisWorker worker = worker(tasks, artifacts, api);

        assertTrue(worker.runOnce());
        assertEquals(List.of("get_forward_msg:forward-1"), actions);
        assertEquals(2, artifacts.edges.size());
        assertEquals("node-1", artifacts.edges.get(0).providerNodeId());
        assertEquals("SUCCEEDED", artifacts.last().status());
        assertTrue(artifacts.last().contentJson().contains("\"node_count\":2"));
    }

    @Test
    void marksEmptyForwardResponseUnavailable() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository(task("EXPAND_FORWARD", "{\"part_id\":1}"));
        FakeArtifactRepository artifacts = new FakeArtifactRepository(forwardPart("forward-empty"));
        BiFunction<String, JsonNode, CompletableFuture<JsonNode>> api = (action, params) -> {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("status", "ok");
            response.putObject("data").putArray("messages");
            return CompletableFuture.completedFuture(response);
        };

        EvidenceAnalysisWorker worker = worker(tasks, artifacts, api);

        assertTrue(worker.runOnce());
        assertEquals(0, artifacts.edges.size());
        assertEquals("UNAVAILABLE", artifacts.last().status());
        assertEquals("FORWARD_FETCH_FAILED", artifacts.last().errorCode());
        assertTrue(tasks.completedResult().contains("FORWARD_FETCH_FAILED"));
    }

    @Test
    void boundsForwardExpansionByDepthAndNodeCount() throws Exception {
        FakeTaskRepository deepTasks = new FakeTaskRepository(task("EXPAND_FORWARD", "{\"part_id\":1}"));
        FakeArtifactRepository deepArtifacts = new FakeArtifactRepository(forwardPart("forward-deep"));
        ObjectNode root = MAPPER.createObjectNode().put("message_id", "depth-0").put("sender_id", "u");
        ObjectNode current = root;
        for (int depth = 1; depth <= 6; depth++) {
            current = current.putArray("content").addObject().put("message_id", "depth-" + depth).put("sender_id", "u");
        }
        BiFunction<String, JsonNode, CompletableFuture<JsonNode>> deepApi = (action, params) ->
                CompletableFuture.completedFuture(responseWithMessages(root));
        EvidenceAnalysisWorker deepWorker = worker(deepTasks, deepArtifacts, deepApi);

        assertTrue(deepWorker.runOnce());
        assertEquals(5, deepArtifacts.edges.size());
        assertTrue(deepArtifacts.edges.stream().allMatch(edge -> edge.depth() <= 4));

        FakeTaskRepository wideTasks = new FakeTaskRepository(task("EXPAND_FORWARD", "{\"part_id\":1}"));
        FakeArtifactRepository wideArtifacts = new FakeArtifactRepository(forwardPart("forward-wide"));
        BiFunction<String, JsonNode, CompletableFuture<JsonNode>> wideApi = (action, params) -> {
            ObjectNode response = MAPPER.createObjectNode();
            response.put("status", "ok");
            var messages = response.putObject("data").putArray("messages");
            for (int i = 0; i < 105; i++) {
                messages.addObject().put("message_id", "node-" + i).put("sender_id", "u").put("content", "x");
            }
            return CompletableFuture.completedFuture(response);
        };
        EvidenceAnalysisWorker wideWorker = worker(wideTasks, wideArtifacts, wideApi);

        assertTrue(wideWorker.runOnce());
        assertEquals(100, wideArtifacts.edges.size());
        assertTrue(wideTasks.completedResult().contains("\"node_count\":100"));
    }

    @Test
    void legacyProfileTaskForShadowGroupIsSkippedBeforeExtraction() throws Exception {
        AnalysisTask task = task("EXTRACT_PROFILE_FACTS", "{}", "437625485");

        assertTrue(EvidenceAnalysisWorker.shouldSkipLegacyProfileExtraction(
                task, MacroReviewMode.LIVE_SHADOW, java.util.Set.of(437625485L)));

        FakeTaskRepository tasks = new FakeTaskRepository(task);
        EvidenceAnalysisWorker worker = worker(tasks, new FakeArtifactRepository(filePart("unused")), null);

        assertTrue(worker.runOnce(MacroReviewMode.LIVE_SHADOW, java.util.Set.of(437625485L)));
        assertTrue(tasks.completedResult().contains("\"status\":\"SKIPPED\""));
        assertTrue(tasks.completedResult().contains("MACRO_REVIEW_OWNS_PROFILE_EXTRACTION"));
    }

    @Test
    void legacyProfileTaskForActiveGroupIsAlsoSkippedBeforeExtraction() {
        AnalysisTask task = task("EXTRACT_PROFILE_FACTS", "{}", "437625485");

        assertTrue(EvidenceAnalysisWorker.shouldSkipLegacyProfileExtraction(
                task, MacroReviewMode.LIVE_ACTIVE, java.util.Set.of(437625485L)));
    }

    @Test
    void legacyProfileTaskForOtherGroupIsNotSkipped() {
        assertFalse(EvidenceAnalysisWorker.shouldSkipLegacyProfileExtraction(
                task("EXTRACT_PROFILE_FACTS", "{}", "group-1"),
                MacroReviewMode.LIVE_SHADOW, java.util.Set.of(437625485L)));
    }

    @Test
    void liveReviewContinuesPagesBeforeSchedulingAndIncludesMessagesArrivingDuringCatchup() {
        assertEquals(24, EvidenceAnalysisWorker.liveReviewWindowLimit(96));
        assertEquals(16, EvidenceAnalysisWorker.liveReviewWindowLimit(16));
        assertEquals(900, EvidenceAnalysisWorker.nextLiveReviewTarget(900, 24, 1100));
        assertEquals(1100, EvidenceAnalysisWorker.nextLiveReviewTarget(900, 900, 1100));
        assertEquals(900, EvidenceAnalysisWorker.nextLiveReviewTarget(900, 900, 850));
    }

    @Test
    void activeWriterPromotesCurrentFactButKeepsFuturePlanAsCandidate() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository(task("MACRO_REVIEW_BATCH", "{}", "group-1"));
        FakeProfileFactRepository facts = new FakeProfileFactRepository();
        EvidenceAnalysisWorker worker = new EvidenceAnalysisWorker(tasks,
                new FakeArtifactRepository(filePart("unused")), new FakeMessageRepository(), facts, null, "test-worker");
        List<Map<String, Object>> window = List.of(
                messageRow(101L, "u1", LocalDateTime.now().minusMinutes(2), "我喜欢策略游戏"),
                messageRow(102L, "u1", LocalDateTime.now().minusMinutes(1), "以后我还是用开源软件"));
        var raw = MacroReviewService.parseJsonObject("""
                {"observations":[
                  {"subject_user_id":"u1","relation_target":"SELF","kind":"interest",
                   "epistemic_type":"SELF_REPORTED","claim_key":"game","claim":"喜欢策略游戏",
                   "polarity":"ASSERT","temporal_scope":"CURRENT_STATE","speech_act":"SELF_ASSERTION",
                   "status":"CANDIDATE","confidence":0.9,"evidence_message_ids":[101]},
                  {"subject_user_id":"u1","relation_target":"SELF","kind":"preference",
                   "epistemic_type":"SELF_REPORTED","claim_key":"software_plan","claim":"以后使用开源软件",
                   "polarity":"ASSERT","temporal_scope":"FUTURE_PLAN","speech_act":"PLAN",
                   "status":"CANDIDATE","confidence":0.9,"evidence_message_ids":[102]}],
                 "stories":[],"group_state":[]}
                """);
        var validated = EvidenceAnalysisWorker.validateMacroResult(raw, window);
        var summary = worker.persistMacroObservations(tasks.next, "group-1", window, validated,
                new MacroReviewService.ReviewResult(raw, "test-model", "test-prompt", 1, 1));

        assertEquals(2, summary.path("candidate_rows_written").asInt());
        assertEquals(1, summary.path("promoted").asInt());
        assertEquals(List.of(1L), facts.promotedCandidateIds);
        assertEquals(1L, validated.path("observations").get(0).path("candidate_id").asLong());
        assertEquals(2L, validated.path("observations").get(1).path("candidate_id").asLong());
        assertEquals("CANDIDATE_STORED_NON_CURRENT",
                validated.path("observations").get(1).path("write_action").asText());
    }

    @Test
    void activeWriterKeepsUnboundedRecentStateOutOfFormalProfile() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository(task("MACRO_REVIEW_BATCH", "{}", "group-1"));
        FakeProfileFactRepository facts = new FakeProfileFactRepository();
        EvidenceAnalysisWorker worker = new EvidenceAnalysisWorker(tasks,
                new FakeArtifactRepository(filePart("unused")), new FakeMessageRepository(), facts, null, "test-worker");
        List<Map<String, Object>> window = List.of(
                messageRow(201L, "u1", LocalDateTime.now(), "我今天在上班"));
        var raw = MacroReviewService.parseJsonObject("""
                {"observations":[
                  {"subject_user_id":"u1","relation_target":"SELF","kind":"recent_state",
                   "epistemic_type":"SELF_REPORTED","claim_key":"work_now","claim":"今天在上班",
                   "polarity":"ASSERT","temporal_scope":"CURRENT_STATE","speech_act":"SELF_ASSERTION",
                   "status":"CANDIDATE","confidence":0.9,"evidence_message_ids":[201]}],
                 "stories":[],"group_state":[]}
                """);

        var validated = EvidenceAnalysisWorker.validateMacroResult(raw, window);
        worker.persistMacroObservations(tasks.next, "group-1", window, validated,
                new MacroReviewService.ReviewResult(raw, "test-model", "test-prompt", 1, 1));

        assertTrue(facts.promotedCandidateIds.isEmpty());
        assertEquals("CANDIDATE_STORED_REQUIRES_VALIDITY",
                validated.path("observations").get(0).path("write_action").asText());
    }

    @Test
    void activeWriterDoesNotPromoteAlreadyExpiredValidity() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository(task("MACRO_REVIEW_BATCH", "{}", "group-1"));
        FakeProfileFactRepository facts = new FakeProfileFactRepository();
        EvidenceAnalysisWorker worker = new EvidenceAnalysisWorker(tasks,
                new FakeArtifactRepository(filePart("unused")), new FakeMessageRepository(), facts, null, "test-worker");
        List<Map<String, Object>> window = List.of(
                messageRow(301L, "u1", LocalDateTime.now(), "我今天在上班"));
        var raw = MacroReviewService.parseJsonObject("""
                {"observations":[
                  {"subject_user_id":"u1","relation_target":"SELF","kind":"recent_state",
                   "epistemic_type":"SELF_REPORTED","claim_key":"work_now","claim":"今天在上班",
                   "polarity":"ASSERT","temporal_scope":"CURRENT_STATE","speech_act":"SELF_ASSERTION",
                   "status":"CANDIDATE","confidence":0.9,"valid_until":"2020-01-01T00:00:00",
                   "evidence_message_ids":[301]}],
                 "stories":[],"group_state":[]}
                """);

        var validated = EvidenceAnalysisWorker.validateMacroResult(raw, window);
        worker.persistMacroObservations(tasks.next, "group-1", window, validated,
                new MacroReviewService.ReviewResult(raw, "test-model", "test-prompt", 1, 1));

        assertTrue(facts.promotedCandidateIds.isEmpty());
        assertEquals("CANDIDATE_STORED_INVALID_VALIDITY",
                validated.path("observations").get(0).path("write_action").asText());
        assertEquals(LocalDateTime.of(2020, 1, 1, 0, 0), facts.candidateInputs.get(0).validUntil());
    }

    @Test
    void groupAtmosphereIsSkippedWhenCognitionIsOff() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository(
                task("ANALYZE_GROUP_ATMOSPHERE", "{\"message_id\":1}"));
        EvidenceAnalysisWorker worker = worker(tasks, new FakeArtifactRepository(filePart("unused")), null);

        assertTrue(worker.runOnce());
        assertTrue(tasks.completedResult().contains("GROUP_COGNITION_DISABLED"));
    }

    private static EvidenceAnalysisWorker worker(FakeTaskRepository tasks, FakeArtifactRepository artifacts,
                                                 BiFunction<String, JsonNode, CompletableFuture<JsonNode>> api) {
        return new EvidenceAnalysisWorker(tasks, artifacts, new MessageRepository(),
                new ProfileFactRepository(null), null, "test-worker", api);
    }

    private static AnalysisTask task(String type, String refs) {
        return task(type, refs, "group-1");
    }

    private static AnalysisTask task(String type, String refs, String groupId) {
        return new AnalysisTask(1, "test-task", type, "PROCESSING", 1, groupId, "user-1", refs,
                1, 3, LocalDateTime.now(), "test-worker", LocalDateTime.now().plusMinutes(1));
    }

    private static Map<String, Object> messageRow(long id, String userId, LocalDateTime createdAt, String content) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("user_id", userId);
        row.put("sender_type", "USER");
        row.put("is_robot_reply", false);
        row.put("is_private", false);
        row.put("availability_status", "AVAILABLE");
        row.put("created_at", createdAt);
        row.put("raw_content", content);
        row.put("content", content);
        return row;
    }

    private static MediaPartInput filePart(String fileId) {
        return new MediaPartInput(1, 10, "file", "{\"type\":\"file\"}", null, null,
                fileId, null, "text/plain", "AVAILABLE");
    }

    private static MediaPartInput forwardPart(String forwardId) {
        return new MediaPartInput(1, 10, "forward", "{\"type\":\"node\",\"data\":{\"id\":\""
                + forwardId + "\"}}", null, null, null, null, null, "AVAILABLE");
    }

    private static JsonNode responseWithMessages(ObjectNode message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("status", "ok");
        response.putObject("data").putArray("messages").add(message);
        return response;
    }

    private static final class FakeTaskRepository extends AnalysisTaskRepository {
        private final AnalysisTask next;
        private String completedResult;

        private FakeTaskRepository(AnalysisTask next) {
            this.next = next;
        }

        @Override
        public Optional<AnalysisTask> claimNext(String owner, int leaseSeconds) {
            return Optional.ofNullable(next);
        }

        @Override
        public Optional<AnalysisTask> claimNextExcludingType(String owner, int leaseSeconds, String taskType) {
            return Optional.ofNullable(next);
        }

        @Override
        public Optional<AnalysisTask> claimNextForType(String owner, int leaseSeconds, String taskType) {
            return Optional.ofNullable(next);
        }

        @Override
        public void complete(AnalysisTask task, String resultJson, String modelName, String parserVersion,
                             Integer inputTokens, Integer outputTokens, Long costMicrounits) {
            completedResult = resultJson;
        }

        private String completedResult() {
            assertNotNull(completedResult);
            return completedResult;
        }
    }

    private static final class FakeArtifactRepository extends MediaArtifactRepository {
        private final MediaPartInput part;
        private final List<ArtifactCall> artifacts = new ArrayList<>();
        private final List<ForwardEdgeCall> edges = new ArrayList<>();

        private FakeArtifactRepository(MediaPartInput part) {
            this.part = part;
        }

        @Override
        public MediaPartInput findPart(long partId) {
            return part.partId() == partId ? part : null;
        }

        @Override
        public long saveArtifact(long partId, String artifactType, String parserName, String parserVersion,
                                 String inputSha256, String status, String contentJson, Double confidence,
                                 String language, String errorCode, String errorDetail,
                                 LocalDateTime startedAt, LocalDateTime completedAt) {
            artifacts.add(new ArtifactCall(partId, artifactType, status, contentJson, errorCode));
            return artifacts.size();
        }

        @Override
        public void saveForwardEdge(long parentPartId, String providerNodeId, int depth, int ordinal,
                                    String senderId, String senderName, LocalDateTime sentAt,
                                    String rawNodeJson, String availabilityStatus) {
            edges.add(new ForwardEdgeCall(providerNodeId, depth, availabilityStatus));
        }

        private ArtifactCall last() {
            assertFalse(artifacts.isEmpty());
            return artifacts.get(artifacts.size() - 1);
        }
    }

    private static final class FakeProfileFactRepository extends ProfileFactRepository {
        private long nextCandidateId = 1;
        private final List<Long> promotedCandidateIds = new ArrayList<>();
        private final List<ProfileFactCandidateInput> candidateInputs = new ArrayList<>();

        private FakeProfileFactRepository() {
            super(null);
        }

        @Override
        public long upsertCandidate(ProfileFactCandidateInput input) {
            candidateInputs.add(input);
            return nextCandidateId++;
        }

        @Override
        public void addEvidence(long candidateId, long messageId, String modality,
                                String quoteOrSpanJson, String availabilitySnapshot, double weight) {
        }

        @Override
        public long promoteCandidate(long candidateId, boolean explicitCorrection) {
            promotedCandidateIds.add(candidateId);
            return candidateId + 100;
        }
    }

    private static final class FakeMessageRepository extends MessageRepository {
        @Override
        public BaseRepository.DatabaseResult<List<Map<String, Object>>> findMessageParts(long messageId) {
            return BaseRepository.DatabaseResult.success(List.of());
        }
    }

    private record ArtifactCall(long partId, String type, String status, String contentJson, String errorCode) {
        private String artifactType() { return type; }
    }

    private record ForwardEdgeCall(String providerNodeId, int depth, String availabilityStatus) { }
}
