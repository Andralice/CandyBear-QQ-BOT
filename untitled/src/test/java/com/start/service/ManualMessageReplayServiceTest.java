package com.start.service;

import com.start.config.BotConfig;
import com.start.runtime.conversation.InMemoryConversationStateStore;
import com.start.service.ManualMessageReplayService.ReplayProfile;
import com.start.service.ManualMessageReplayService.Turn;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualMessageReplayServiceTest {
    private HikariDataSource dataSource;
    private ManualMessageReplayService replay;
    private final List<Turn> turns = new ArrayList<>();
    private final AtomicInteger generated = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:manual_replay;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS media_artifacts");
            statement.execute("DROP TABLE IF EXISTS message_parts");
            statement.execute("DROP TABLE IF EXISTS messages");
            statement.execute("CREATE TABLE messages ("
                    + "id BIGINT PRIMARY KEY, user_id VARCHAR(50), group_id VARCHAR(50), content CLOB, "
                    + "raw_segments_json CLOB, raw_event_json CLOB, image_data CLOB, is_robot_reply BOOLEAN, "
                    + "sender_type VARCHAR(16), provider_message_id VARCHAR(64), reply_to_id BIGINT, "
                    + "reply_provider_message_id VARCHAR(64), availability_status VARCHAR(32), "
                    + "created_at TIMESTAMP, is_private BOOLEAN, retracted_at TIMESTAMP NULL, redacted_at TIMESTAMP NULL)");
            statement.execute("CREATE TABLE message_parts (id BIGINT PRIMARY KEY, message_id BIGINT, part_index INT, "
                    + "part_type VARCHAR(32), raw_part_json CLOB, text_value CLOB, source_url CLOB, "
                    + "provider_file_id VARCHAR(255), target_id VARCHAR(64), mime_type VARCHAR(128), byte_size BIGINT, "
                    + "sha256 VARCHAR(64), storage_key VARCHAR(512), availability_status VARCHAR(32), error_code VARCHAR(64))");
            statement.execute("CREATE TABLE media_artifacts (id BIGINT PRIMARY KEY, part_id BIGINT, "
                    + "artifact_type VARCHAR(40), status VARCHAR(32), content_json CLOB, error_code VARCHAR(64), "
                    + "error_detail CLOB, input_sha256 VARCHAR(64), parser_name VARCHAR(80), "
                    + "parser_version VARCHAR(40), completed_at TIMESTAMP)");
            insert(statement, 1, "10", "测试前上下文", false, null);
            insert(statement, 2, "11", "说A", false, null);
            insert(statement, 3, "900", "真实旧回复", true, 2L);
            insert(statement, 4, "12", "说B", false, null);
            insert(statement, 5, "13", "未来内容", false, null);
        }
        var candidate = MainModelPool.Candidate.fromBaseUrl(
                "test", "http://127.0.0.1:1/v1", "test-model", "openai-compatible", "", "test-key");
        replay = new ManualMessageReplayService(dataSource,
                () -> new ReplayProfile("test-model", candidate, null, null, null),
                (turn, profile) -> {
                    turns.add(turn);
                    return new ManualMessageReplayService.ReplayGeneration("重放回复" + generated.incrementAndGet(),
                            turn.turnEvidence(), turn.modelUserText(), "OPENAI", turn.imageDataUris().size(),
                            "test-hash", null, turn.localMessageId() == 4);
                }, new ConversationInterpreter(new InMemoryConversationStateStore(
                        new java.util.concurrent.ConcurrentHashMap<>(),
                        new java.util.concurrent.ConcurrentHashMap<>(),
                        new java.util.concurrent.ConcurrentHashMap<>(),
                        new java.util.concurrent.ConcurrentHashMap<>()), new AIDatabaseService()));
    }

    @AfterEach
    void tearDown() {
        if (replay != null) replay.close();
        if (dataSource != null) dataSource.close();
    }

    @Test
    void historicalReplayAdvancesWithoutCallsAndUsesOnlyPersistedChronologicalPrefix() throws Exception {
        var start = replay.start("437625485", 2, 5);
        assertEquals(4, start.messages().size());
        assertEquals("HISTORICAL", start.mode());
        assertEquals(2, start.nextLocalMessageId());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE messages SET content='数据库后来变化' WHERE id IN (1,2,3)");
        }

        var observed = replay.advance(start.replayId(), 2);
        assertEquals("HISTORICAL", observed.mode());
        assertEquals(4L, observed.nextLocalMessageId());
        assertEquals(0, generated.get());
        assertEquals(observed, replay.advance(start.replayId(), 2));

        var firstTarget = replay.inject(start.replayId(), 4);
        assertFalse(turns.get(0).allowSilence());
        assertEquals("说B", turns.get(0).prompt());
        assertTrue(firstTarget.transcript().contains("测试前上下文"));
        assertTrue(firstTarget.transcript().contains("说A"));
        assertTrue(firstTarget.transcript().contains("真实旧回复"));
        assertFalse(firstTarget.transcript().contains("重放回复"));
        assertFalse(firstTarget.transcript().contains("数据库后来变化"));
        assertFalse(firstTarget.transcript().contains("未来内容"));
        assertFalse(firstTarget.contextMessageIds().stream().anyMatch(id -> id.startsWith("replay:")));
        assertTrue(firstTarget.replyToCurrentMessage());
        assertEquals(5L, firstTarget.nextLocalMessageId());

        var secondTarget = replay.inject(start.replayId(), 5);
        assertTrue(secondTarget.transcript().contains("真实旧回复"));
        assertFalse(secondTarget.transcript().contains("重放回复1"));
        assertFalse(secondTarget.replyToCurrentMessage());
        assertEquals(2, turns.size());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM messages")) {
            result.next();
            assertEquals(5, result.getInt(1));
        }
    }

    @Test
    void counterfactualModeExplicitlyFeedsGeneratedRepliesIntoLaterTurns() {
        var start = replay.start("437625485", 2, 5,
                ManualMessageReplayService.ReplayMode.COUNTERFACTUAL);
        assertEquals("COUNTERFACTUAL", start.mode());
        var advanceError = assertThrows(ManualMessageReplayService.ReplayException.class,
                () -> replay.advance(start.replayId(), 2));
        assertEquals(409, advanceError.status());

        replay.inject(start.replayId(), 2);
        var second = replay.inject(start.replayId(), 4);
        assertTrue(second.transcript().contains("重放回复1"));
        assertFalse(second.transcript().contains("真实旧回复"));
        assertTrue(second.contextMessageIds().contains("replay:2"));
    }

    @Test
    void injectionIsOrderedAndRetryingACompletedStepDoesNotCallTheModelTwice() {
        var start = replay.start("437625485", 2, 5);
        var outOfOrder = assertThrows(ManualMessageReplayService.ReplayException.class,
                () -> replay.inject(start.replayId(), 4));
        assertEquals(409, outOfOrder.status());
        assertEquals(0, generated.get());

        var first = replay.inject(start.replayId(), 2);
        var retry = replay.inject(start.replayId(), 2);
        assertEquals(first, retry);
        assertEquals(1, generated.get());
    }

    @Test
    void proactiveCandidateReplayUsesTheRealInterpreterAndAllowsTheModelToStaySilent() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "大家最近推荐什么游戏？", false, null);
        }

        var start = replay.start("437625485", 6, 6);
        var result = replay.inject(start.replayId(), 6, true);

        assertEquals("PROBABILISTIC", result.triggerEvent());
        assertTrue(result.proactiveCandidate());
        assertTrue(result.modelCallMade());
        assertEquals("REPLY", result.decision());
        assertTrue(turns.get(0).allowSilence());
        assertTrue(turns.get(0).prompt().contains("旧主动话题关键词命中的候选"));
        assertTrue(result.modelUserText().contains("大家最近推荐什么游戏？"));
        assertTrue(result.turnEvidence().contains("大家最近推荐什么游戏？"));
        assertEquals(1, generated.get());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM messages")) {
            rows.next();
            assertEquals(6, rows.getInt(1));
        }
    }

    @Test
    void proactiveCandidateReplaySkipsThirdPartyAddressedKeywordMessagesWithoutCallingModel() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "安洁，你推荐什么游戏？", false, null);
        }

        var start = replay.start("437625485", 6, 6);
        var result = replay.inject(start.replayId(), 6, true);

        assertEquals("NOTHING", result.triggerEvent());
        assertFalse(result.proactiveCandidate());
        assertFalse(result.modelCallMade());
        assertEquals("NO_CANDIDATE", result.decision());
        assertEquals(0, generated.get());
    }

    @Test
    void historicalBotMessagesAtTheStartOfTheReplayRangeRemainContext() {
        var start = replay.start("437625485", 3, 4);
        var result = replay.inject(start.replayId(), 4);
        assertTrue(result.transcript().contains("真实旧回复"));
        assertFalse(result.transcript().contains("未来内容"));
    }

    @Test
    void resolvesProviderReplyIdAndPlacesFullStructuredEvidenceInActualSystemPrompt() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "被引用的原话", false, null, "provider-quoted", null, "[]");
            insert(statement, 7, "21", "这是对引用的回复", false, 991827L, "provider-current", "provider-quoted", "[]");
        }

        var start = replay.start("437625485", 7, 7);
        var result = replay.inject(start.replayId(), 7);
        Turn turn = turns.get(0);

        assertEquals(6L, result.replyToLocalMessageId());
        assertEquals("provider-quoted", result.replyProviderMessageId());
        assertEquals("991827", result.replyToRawId());
        assertEquals("provider-quoted", result.replyReference());
        assertEquals("REPLY_PROVIDER_MESSAGE_ID", result.replyReferenceSource());
        assertEquals("RESOLVED_PROVIDER_ID", result.replyResolutionStatus());
        assertTrue(turn.turnEvidence().contains("reply_provider_message_id=provider-quoted"));
        assertTrue(turn.turnEvidence().contains("reply_to_id_raw=991827"));
        assertTrue(turn.turnEvidence().contains("reply_reference_source=REPLY_PROVIDER_MESSAGE_ID"));
        assertTrue(turn.turnEvidence().contains("local_id=6"));
        assertTrue(turn.turnEvidence().contains("reply_to_local_id=6"));
        assertTrue(turn.turnEvidence().contains("reply_to_content=被引用的原话"));
        assertTrue(result.systemPrompt().contains("local_id=6"));
        assertTrue(result.systemPrompt().contains("这是对引用的回复"));
        assertTrue(result.modelUserText().contains("这是对引用的回复"));
        assertTrue(result.contextMessageIds().contains("6"));
        assertTrue(result.contextMessageIds().contains("7"));
        String renderedSystemPrompt = new PromptBuilder().build("base rules", new PromptContext()
                .groupId("437625485").turnEvidence(turn.turnEvidence())
                .publicGroupContext("THIS_FLAT_TRANSCRIPT_MUST_NOT_BE_THE_ONLY_CONTEXT"));
        assertTrue(renderedSystemPrompt.contains("被引用的原话"));
        assertFalse(renderedSystemPrompt.contains("THIS_FLAT_TRANSCRIPT_MUST_NOT_BE_THE_ONLY_CONTEXT"));
    }

    @Test
    void resolvesLegacyReplyToIdWithoutLabelingItAsAnExplicitProviderId() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "平台消息目标", false, null, "900006", null, "[]");
            insert(statement, 7, "21", "旧字段引用平台消息", false, 900006L, "provider-current", null, "[]");
            insert(statement, 8, "22", "本地行目标", false, null, "provider-local", null, "[]");
            insert(statement, 9, "23", "旧字段引用本地行", false, 8L, "provider-local-ref", null, "[]");
        }

        var providerStart = replay.start("437625485", 7, 7);
        var providerResult = replay.inject(providerStart.replayId(), 7);
        assertEquals(6L, providerResult.replyToLocalMessageId());
        assertTrue(providerResult.replyProviderMessageId() == null);
        assertEquals("900006", providerResult.replyToRawId());
        assertEquals("900006", providerResult.replyReference());
        assertEquals("REPLY_TO_ID_LEGACY_AMBIGUOUS", providerResult.replyReferenceSource());
        assertEquals("RESOLVED_PROVIDER_ID", providerResult.replyResolutionStatus());
        assertTrue(providerResult.turnEvidence().contains("reply_provider_message_id=未知"));
        assertTrue(providerResult.turnEvidence().contains("reply_to_id_raw=900006"));
        assertTrue(providerResult.turnEvidence().contains("reply_to_content=平台消息目标"));

        var localStart = replay.start("437625485", 9, 9);
        var localResult = replay.inject(localStart.replayId(), 9);
        assertEquals(8L, localResult.replyToLocalMessageId());
        assertEquals("REPLY_TO_ID_LEGACY_AMBIGUOUS", localResult.replyReferenceSource());
        assertEquals("RESOLVED_LEGACY_LOCAL_ID", localResult.replyResolutionStatus());
        assertTrue(localResult.turnEvidence().contains("reply_to_content=本地行目标"));
    }

    @Test
    void unresolvedReplyReturnsTheWholeAvailableEvidenceWithoutCallingTheModel() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "引用目标已不在数据库", false, null, "provider-existing", null, "[]");
            insert(statement, 7, "21", "这条引用无法解析", false, 999999L, "provider-current", null, "[]");
        }

        var start = replay.start("437625485", 7, 7);
        var failure = assertThrows(ManualMessageReplayService.ReplayException.class,
                () -> replay.inject(start.replayId(), 7));

        assertEquals("reply_target_unresolved", failure.code());
        assertEquals(0, generated.get());
        assertEquals(false, failure.details().get("modelCallMade"));
        assertEquals("999999", failure.details().get("replyToRawId"));
        assertEquals("999999", failure.details().get("replyReference"));
        assertEquals("REPLY_TO_ID_LEGACY_AMBIGUOUS", failure.details().get("replyReferenceSource"));
        assertEquals("PROVIDER_TARGET_NOT_FOUND", failure.details().get("replyResolutionStatus"));
        assertFalse(String.valueOf(failure.details().get("transcript")).contains("这条引用无法解析"));
        assertTrue(String.valueOf(failure.details().get("turnEvidence")).contains("这条引用无法解析"));
        assertTrue(String.valueOf(failure.details().get("turnEvidence")).contains("reply_to_id_raw=999999"));
        assertTrue(String.valueOf(failure.details().get("turnEvidence")).contains("reply_target=UNRESOLVED"));
    }

    @Test
    void loadsVisionArtifactFromMessagePartAndIncludesItInPromptEvidence() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "发了一张图", false, null, "provider-image", null,
                    "[{\"type\":\"image\",\"data\":{}}]");
            insert(statement, 7, "21", "图里有什么", false, 991827L, "provider-current", "provider-image", "[]");
            statement.execute("INSERT INTO message_parts VALUES (501,6,0,'image','{}',NULL,NULL,NULL,NULL,"
                    + "'image/png',123,'image-hash',NULL,'PENDING',NULL)");
            statement.execute("INSERT INTO media_artifacts (id,part_id,artifact_type,status,content_json,error_code,"
                    + "error_detail,input_sha256,parser_name,parser_version,completed_at) VALUES (601,501,'IMAGE_DESCRIPTION','SUCCEEDED',"
                    + "'{\"description\":\"画面里有一只橙色猫\"}',NULL,NULL,'image-hash','vision','v1',"
                    + "TIMESTAMP '2026-10-01 12:02:00')");
        }

        var start = replay.start("437625485", 7, 7);
        var result = replay.inject(start.replayId(), 7);

        assertTrue(result.turnEvidence().contains("画面里有一只橙色猫"));
        assertTrue(result.systemPrompt().contains("画面里有一只橙色猫"));
        assertTrue(result.imageEvidence().stream().anyMatch(image -> image.messageLocalId() == 6
                && image.partId() == 501L && image.artifactId() == 601L
                && image.status().equals("SUCCEEDED") && image.descriptionInPrompt()));
    }

    @Test
    void blocksModelCallWhenQuotedImageEvidenceFailedAndReturnsProvenance() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "发了一张图", false, null, "provider-image", null,
                    "[{\"type\":\"image\",\"data\":{}}]");
            insert(statement, 7, "21", "图里有什么", false, 991827L, "provider-current", "provider-image", "[]");
            statement.execute("INSERT INTO message_parts VALUES (502,6,0,'image','{}',NULL,NULL,NULL,NULL,"
                    + "'image/png',123,'failed-image-hash',NULL,'UNAVAILABLE_REFERENCE',NULL)");
            statement.execute("INSERT INTO media_artifacts (id,part_id,artifact_type,status,content_json,error_code,"
                    + "error_detail,input_sha256) VALUES (602,502,'IMAGE_DESCRIPTION','FAILED',"
                    + "NULL,'VISION_EMPTY_RESULT','no verifiable description','failed-image-hash')");
        }

        var start = replay.start("437625485", 7, 7);
        var failure = assertThrows(ManualMessageReplayService.ReplayException.class,
                () -> replay.inject(start.replayId(), 7));

        assertEquals(424, failure.status());
        assertEquals("required_image_evidence_unavailable", failure.code());
        assertEquals(0, generated.get());
        assertEquals(6L, ((Number) failure.details().get("messageLocalId")).longValue());
        assertEquals(602L, ((Number) failure.details().get("artifactId")).longValue());
        assertEquals("FAILED", failure.details().get("visionStatus"));
        assertEquals("VISION_EMPTY_RESULT", failure.details().get("errorCode"));
        assertEquals(false, failure.details().get("modelCallMade"));
        assertTrue(failure.details().containsKey("transcript"));
        assertTrue(String.valueOf(failure.details().get("turnEvidence")).contains("无可验证识图结果"));
        assertTrue(failure.details().get("imageEvidence") instanceof List<?>);
    }

    @Test
    void waitsForPendingVisionAndRefreshesOnlyDerivedEvidence() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "发了一张图", false, null, "provider-image", null,
                    "[{\"type\":\"image\",\"data\":{}}]");
            insert(statement, 7, "21", "图里有什么", false, 991827L, "provider-current", "provider-image", "[]");
            statement.execute("INSERT INTO message_parts VALUES (503,6,0,'image','{}',NULL,NULL,NULL,NULL,"
                    + "'image/png',123,'pending-image-hash',NULL,'PENDING',NULL)");
            statement.execute("INSERT INTO media_artifacts (id,part_id,artifact_type,status,content_json,error_code,"
                    + "error_detail,input_sha256) VALUES (603,503,'IMAGE_DESCRIPTION','PENDING',"
                    + "NULL,NULL,NULL,'pending-image-hash')");
        }

        var start = replay.start("437625485", 7, 7);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE media_artifacts SET status='SUCCEEDED', "
                    + "content_json='{\"description\":\"识图完成\"}' WHERE id=603");
        }

        var result = replay.inject(start.replayId(), 7);
        assertTrue(result.turnEvidence().contains("识图完成"));
        assertTrue(result.imageEvidence().stream().anyMatch(image -> image.artifactId() == 603L
                && image.status().equals("SUCCEEDED")));
    }

    @Test
    void rejectsInvalidLegacyBase64WithoutLeakingItOrCallingTheModel() throws Exception {
        String payload = "SECRET_IMAGE_PAYLOAD!!!";
        String rawContent = "前文[CQ:image,file=base64://" + payload + "]后文";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", rawContent, false, null, "provider-image-text", null, "[]");
        }

        var start = replay.start("437625485", 6, 6);
        assertEquals("前文[图片]后文", start.messages().get(0).content());
        assertEquals("INVALID_BASE64", start.messages().get(0).images().get(0).inlinePayloadStatus());

        var error = assertThrows(ManualMessageReplayService.ReplayException.class,
                () -> replay.inject(start.replayId(), 6));
        assertEquals("required_image_evidence_unavailable", error.code());
        assertEquals(0, generated.get());
        String evidence = String.valueOf(error.details().get("turnEvidence"));
        String transcript = String.valueOf(error.details().get("transcript"));
        assertTrue(evidence.contains("content=前文[图片]后文"));
        assertFalse(transcript.contains(payload));
        assertFalse(evidence.contains(payload));
        assertFalse(transcript.contains("base64://"));
        assertFalse(evidence.contains("[CQ:image"));
    }

    @Test
    void attachesValidatedLegacyInlineImageAndRecordsOnlySafeEvidence() throws Exception {
        String payload = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/SNEAAAAASUVORK5CYII=";
        byte[] bytes = java.util.Base64.getDecoder().decode(payload);
        String expectedHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "看图[CQ:image,file=base64://" + payload + "]", false,
                    null, "provider-inline-image", null, "[]");
        }

        boolean previous = setMultimodalEnabled(true);
        try {
            var start = replay.start("437625485", 6, 6);
            assertEquals("AVAILABLE", start.messages().get(0).images().get(0).inlinePayloadStatus());
            var result = replay.inject(start.replayId(), 6);

            assertEquals(1, turns.get(0).imageDataUris().size());
            assertEquals(1, turns.get(0).searchImages().size());
            assertEquals(1, turns.get(0).searchImages().get(0).selectionIndex());
            assertEquals(1, turns.get(0).searchImages().get(0).messageImageIndex());
            assertTrue(turns.get(0).imageDataUris().get(0).startsWith("data:image/png;base64,"));
            assertEquals(1, result.attachedImageCount());
            var image = result.imageEvidence().stream()
                    .filter(item -> item.messageLocalId() == 6).findFirst().orElseThrow();
            assertEquals("INLINE_ATTACHED", image.sourceLoadStatus());
            assertEquals("cq-inline-base64", image.sourceScheme());
            assertEquals(bytes.length, image.inlinePayloadBytes());
            assertEquals(expectedHash, image.sha256());
            assertTrue(image.attachedAsImage());
            assertFalse(start.messages().get(0).content().contains(payload));
            assertFalse(result.transcript().contains(payload));
            assertFalse(result.turnEvidence().contains(payload));
            assertFalse(result.modelUserText().contains(payload));
            assertTrue(result.turnEvidence().contains(expectedHash));
        } finally {
            setMultimodalEnabled(previous);
        }
    }

    @Test
    void doesNotAttachAnUnrelatedHistoricalInlineImage() throws Exception {
        String payload = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/SNEAAAAASUVORK5CYII=";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insert(statement, 6, "20", "前文图[CQ:image,file=base64://" + payload + "]", false,
                    null, "provider-old-image", null, "[]");
            insert(statement, 7, "21", "这是后续无关文字", false, null,
                    "provider-unrelated-text", null, "[]");
        }

        boolean previous = setMultimodalEnabled(true);
        try {
            var start = replay.start("437625485", 7, 7);
            var result = replay.inject(start.replayId(), 7);

            assertTrue(turns.get(0).imageDataUris().isEmpty());
            var priorImage = result.imageEvidence().stream()
                    .filter(item -> item.messageLocalId() == 6).findFirst().orElseThrow();
            assertEquals("NONE", priorImage.inlinePayloadStatus());
            assertFalse(result.transcript().contains(payload));
            assertFalse(result.turnEvidence().contains(payload));
            assertFalse(result.modelUserText().contains(payload));
        } finally {
            setMultimodalEnabled(previous);
        }
    }

    @Test
    void imageDownloadErrorCodesDoNotExposeExceptionText() {
        assertEquals("NON_PUBLIC_HOST_BLOCKED", ManualMessageReplayService.safeImageLoadErrorCode(
                new AgnesMediaService.AgnesMediaException("参考图片地址必须可从公网访问")));
        assertEquals("HTTP_403", ManualMessageReplayService.safeImageLoadErrorCode(
                new AgnesMediaService.AgnesMediaException("读取参考图片失败（HTTP 403），请重新发送原图")));
        assertEquals("HTTP_400_SIGNATURE_REJECTED", ManualMessageReplayService.safeImageLoadErrorCode(
                new AgnesMediaService.AgnesMediaException("读取参考图片失败（HTTP 400，REMOTE_SIGNATURE_REJECTED），请重新发送原图")));
        assertEquals("HOST_LOOKUP_FAILED", ManualMessageReplayService.safeImageLoadErrorCode(
                new java.net.UnknownHostException("sensitive-host.example/private-token")));
    }

    private static void insert(Statement statement, long id, String userId, String content,
                               boolean bot, Long replyTo) throws Exception {
        insert(statement, id, userId, content, bot, replyTo, null, null, "[]");
    }

    private static void insert(Statement statement, long id, String userId, String content,
                               boolean bot, Long replyTo, String providerId, String replyProviderId,
                               String rawSegments) throws Exception {
        String rawEvent = "{\"sender\":{\"nickname\":\"member-" + userId + "\"}}";
        statement.execute("INSERT INTO messages (id,user_id,group_id,content,raw_segments_json,raw_event_json,"
                + "image_data,is_robot_reply,sender_type,provider_message_id,reply_to_id,reply_provider_message_id,"
                + "availability_status,created_at,is_private,retracted_at,redacted_at) VALUES ("
                + id + ", '" + userId + "', '437625485', '" + sql(content) + "', '" + sql(rawSegments)
                + "', '" + sql(rawEvent) + "', NULL, " + bot + ", '" + (bot ? "BOT" : "USER") + "', "
                + nullableString(providerId) + ", " + (replyTo == null ? "NULL" : replyTo) + ", "
                + nullableString(replyProviderId) + ", 'AVAILABLE', TIMESTAMP '2026-10-01 12:00:00', FALSE, NULL, NULL)");
    }

    private static String nullableString(String value) {
        return value == null ? "NULL" : "'" + sql(value) + "'";
    }

    private static String sql(String value) {
        return value.replace("'", "''");
    }

    private static boolean setMultimodalEnabled(boolean enabled) throws Exception {
        var field = BotConfig.class.getDeclaredField("baiLianMultimodalEnabled");
        field.setAccessible(true);
        boolean previous = field.getBoolean(null);
        field.setBoolean(null, enabled);
        return previous;
    }
}
