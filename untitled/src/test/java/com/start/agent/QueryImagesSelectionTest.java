package com.start.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.repository.MessageRepository;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class QueryImagesSelectionTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 12, 0);

    @Test void exactlyFiveMinutesIsRecentButOlderOrUnknownRequiresConfirmation() throws Exception {
        var recent = image(1, NOW.minusMinutes(5));
        var older = image(2, NOW.minusMinutes(5).minusNanos(1_000_000));
        var unknown = image(3, null);
        QueryImagesTool tool = new QueryImagesTool(new Rows(List.of(recent, older, unknown)), "group", "session", "alice", NOW);
        var items = new ObjectMapper().readTree(tool.execute(Map.of("include_older", true))).path("items");
        assertFalse(items.get(0).path("requires_confirmation").asBoolean());
        assertTrue(items.get(1).path("requires_confirmation").asBoolean());
        assertTrue(items.get(2).path("requires_confirmation").asBoolean());
    }

    @Test void imagesWithoutVisionOrUrlRemainDistinctUnavailableCandidates() throws Exception {
        Map<String, Object> row = image(1, NOW.minusMinutes(1));
        row.put("raw_segments_json", "[{\"type\":\"image\",\"data\":{\"file\":\"qq-file-1\"}},{\"type\":\"image\",\"data\":{\"file\":\"qq-file-2\"}}]");
        QueryImagesTool tool = new QueryImagesTool(new Rows(List.of(row)), "group", "session", "alice", NOW);
        var items = new ObjectMapper().readTree(tool.execute(Map.of())).path("items");
        assertEquals(2, items.size());
        assertEquals("1:1", items.get(0).path("image_id").asText());
        assertEquals("1:2", items.get(1).path("image_id").asText());
        assertEquals("UNAVAILABLE", items.get(1).path("source_status").asText());
        assertThrows(IllegalArgumentException.class, () -> tool.resolveInputs(List.of("1:2"), List.of(), null));
    }

    @Test void privateLookupRejectsOtherUserBeforeRepositoryAccess() {
        QueryImagesTool tool = new QueryImagesTool(new Rows(List.of()), null, "private-alice", "alice", NOW);
        assertTrue(tool.execute(Map.of("user_id", "bob")).contains("拒绝"));
        assertTrue(tool.execute(Map.of("group_id", "other")).contains("拒绝"));
    }

    @Test void duplicateImagesKeepTheirMessageOrderAndDescription() throws Exception {
        Map<String, Object> row = image(42, NOW.minusMinutes(1));
        row.put("image_data", "[{\"url\":\"https://example.com/same.png\",\"desc\":\"第一张\"},"
                + "{\"url\":\"https://example.com/same.png\",\"desc\":\"第二张\"}]");
        row.put("raw_segments_json", "[{\"type\":\"image\",\"data\":{\"url\":\"https://example.com/same.png\"}},"
                + "{\"type\":\"image\",\"data\":{\"url\":\"https://example.com/same.png\"}}]");

        QueryImagesTool tool = new QueryImagesTool(new Rows(List.of(row)), "group", "session", "alice", NOW);
        var items = new ObjectMapper().readTree(tool.execute(Map.of())).path("items");

        assertEquals(2, items.size());
        assertEquals("42:1", items.get(0).path("image_id").asText());
        assertEquals("42:2", items.get(1).path("image_id").asText());
        assertEquals(1, items.get(0).path("image_index").asInt());
        assertEquals(2, items.get(1).path("image_index").asInt());
        assertEquals("第一张", items.get(0).path("description").asText());
        assertEquals("第二张", items.get(1).path("description").asText());
    }

    @Test void httpFileFieldIsUsableWhenProviderOmitsUrl() throws Exception {
        Map<String, Object> row = image(44, NOW.minusMinutes(1));
        row.put("raw_segments_json", "[{\"type\":\"image\",\"data\":{\"file\":\"https://example.com/from-file.png\"}}]");
        QueryImagesTool tool = new QueryImagesTool(new Rows(List.of(row)), "group", "session", "alice", NOW);

        var item = new ObjectMapper().readTree(tool.execute(Map.of())).path("items").get(0);

        assertEquals("RECORDED_NOT_DOWNLOADED", item.path("source_status").asText());
        assertEquals("44:1", item.path("image_id").asText());
    }

    @Test void robotImageResultNamesCandyBearAsSender() throws Exception {
        Map<String, Object> row = image(43, NOW.minusMinutes(1));
        row.put("user_id", "candybear");
        row.put("is_robot_reply", true);
        row.put("content", "[CQ:image,file=https://example.com/own.png]");
        row.put("raw_segments_json", "[{\"type\":\"text\",\"data\":{\"text\":\"图片\"}}]");
        row.put("image_data", null);

        QueryImagesTool tool = new QueryImagesTool(new Rows(List.of(row)), "group", "session", "alice", NOW);
        var item = new ObjectMapper().readTree(tool.execute(Map.of("user_id", "candybear")))
                .path("items").get(0);

        assertEquals("糖果熊", item.path("sender").asText());
        assertEquals("candybear", item.path("user_id").asText());
        assertEquals("43:1", item.path("image_id").asText());
    }

    private static Map<String, Object> image(long id, LocalDateTime time) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id); row.put("user_id", "alice"); row.put("content", "参考图片");
        row.put("raw_segments_json", "[{\"type\":\"image\",\"data\":{\"url\":\"https://example.com/a.png\"}}]");
        if (time != null) row.put("created_at", Timestamp.valueOf(time));
        return row;
    }

    private static class Rows extends MessageRepository {
        private final List<Map<String, Object>> rows;
        Rows(List<Map<String, Object>> rows) { this.rows = rows; }
        @Override public DatabaseResult<List<Map<String, Object>>> searchImageMessages(String group, String session, String user,
                String keyword, long id, String provider, boolean older, LocalDateTime time, long cursor, int limit) {
            return DatabaseResult.success(rows);
        }
    }
}
