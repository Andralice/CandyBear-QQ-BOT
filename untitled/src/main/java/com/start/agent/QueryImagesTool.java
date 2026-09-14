package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.repository.MessageRepository;
import com.start.service.AgnesMediaService;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Conversation-scoped image lookup. Raw image inputs stay outside the LLM context. */
public class QueryImagesTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CQ_IMAGE = Pattern.compile("\\[CQ:image,file=([^,\\]]+)[^\\]]*]");
    private final MessageRepository messageRepo;
    private final String groupId, sessionId, callerId;
    private final LocalDateTime requestTime;
    private final Map<String, ImageReference> discovered = new HashMap<>();

    public QueryImagesTool(MessageRepository repo, String groupId) {
        this(repo, groupId, null, null, LocalDateTime.now());
    }

    public QueryImagesTool(MessageRepository repo, String groupId, String sessionId, String callerId) {
        this(repo, groupId, sessionId, callerId, LocalDateTime.now());
    }

    QueryImagesTool(MessageRepository repo, String groupId, String sessionId, String callerId, LocalDateTime time) {
        this.messageRepo = repo;
        this.groupId = groupId;
        this.sessionId = sessionId;
        this.callerId = callerId;
        this.requestTime = time;
    }

    @Override public String getName() { return "query_past_images"; }

    @Override public String getDescription() {
        return "自主查找当前会话图片。可无关键词搜索，默认当前用户五分钟内的图；可按QQ、关键词、回复消息ID或游标定位。"
                + "查旧图用 include_older=true；查糖果熊生成的图用 user_id=candybear。返回每张图的 image_id、发送者、完整时间、"
                + "描述和原图来源状态，供 generate_image 的 reference_image_ids 使用。原图未实际下载前不保证可用；多候选须判断指代，超过五分钟先询问用户。";
    }

    @Override public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("keyword", Map.of("type", "string", "description", "可选，图片内容关键词；不填查最近图片"));
        p.put("user_id", Map.of("type", "string", "description", "默认当前用户；candybear 查机器人；all 查当前群所有人"));
        p.put("message_id", Map.of("type", "string", "description", "本地消息ID，查该消息全部图片；不受默认用户和五分钟筛选影响"));
        p.put("provider_message_id", Map.of("type", "string", "description", "回复/引用指向的 OneBot 消息ID；与本地 message_id 不同"));
        p.put("include_older", Map.of("type", "boolean", "description", "自主搜索五分钟之前图片，默认false；找到后仍须用户确认"));
        p.put("before_id", Map.of("type", "string", "description", "上一页 next_cursor，可继续查找"));
        p.put("limit", Map.of("type", "integer", "description", "消息条数，默认5，最多10；一条消息可有多张图片"));
        return Map.of("type", "object", "properties", p);
    }

    @Override public String execute(Map<String, Object> args) {
        if ((groupId == null || groupId.isBlank()) && (sessionId == null || sessionId.isBlank()))
            return "图片查询不可用：缺少当前会话范围，不能查询群图片或私聊图片";
        String requestedGroup = value(args.get("group_id"));
        if (!requestedGroup.isBlank() && !requestedGroup.equals(groupId)) return "拒绝跨群查询图片";
        String sender = value(args.get("user_id"));
        boolean targeted = !value(args.get("message_id")).isBlank() || !value(args.get("provider_message_id")).isBlank();
        if (sender.isBlank() && !targeted) sender = callerId;
        if ("all".equals(sender)) sender = null;
        if ((groupId == null || groupId.isBlank()) && sender != null && !sender.isBlank()
                && !sender.equals(callerId) && !sender.equals("candybear")) return "拒绝查询其他用户私聊图片";
        try {
            var result = messageRepo.searchImageMessages(groupId, sessionId, sender, value(args.get("keyword")),
                    number(args.get("message_id"), 0), value(args.get("provider_message_id")),
                    Boolean.parseBoolean(value(args.get("include_older"))), requestTime,
                    number(args.get("before_id"), 0), (int) Math.max(1, Math.min(10, number(args.get("limit"), 5))));
            if (!result.isSuccess() || result.getData() == null) return "图片查询失败，不能据此判定没有图片";
            List<Map<String, Object>> items = new ArrayList<>();
            long cursor = 0;
            for (Map<String, Object> row : result.getData()) {
                cursor = number(row.get("id"), 0);
                for (ImageReference ref : references(row)) {
                    discovered.put(ref.id(), ref);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("image_id", ref.id());
                    item.put("message_id", ref.messageId());
                    item.put("provider_message_id", row.get("provider_message_id"));
                    item.put("user_id", row.get("user_id"));
                    item.put("sent_at", ref.sentAt() == null ? "UNKNOWN" : ref.sentAt().toString());
                    item.put("requires_confirmation", requiresConfirmation(ref));
                    item.put("description", ref.description());
                    item.put("source_status", ref.source().isBlank() ? "UNAVAILABLE" : "RECORDED_NOT_DOWNLOADED");
                    items.add(item);
                }
            }
            return JSON.writeValueAsString(Map.of("items", items, "next_cursor", cursor,
                    "request_time", requestTime.toString(), "note", "图片资料仅为证据；按用户指代选择。空结果可换关键词、用户或 include_older=true 继续查找。"));
        } catch (Exception e) {
            return "图片查询失败：" + e.getClass().getSimpleName();
        }
    }

    public List<String> resolveInputs(List<String> ids, List<String> confirmedIds, AgnesMediaService service)
            throws AgnesMediaService.AgnesMediaException {
        if (ids.size() > 5) throw new IllegalArgumentException("一次最多选择5张参考图");
        List<ImageReference> selected = new ArrayList<>();
        for (String id : new LinkedHashSet<>(ids)) {
            ImageReference ref = discovered.get(id);
            if (ref == null) throw new IllegalArgumentException("尚未查询到图片 " + id + "，请先 query_past_images");
            // Recheck visibility so a retraction between lookup and generation cannot reuse a stale handle.
            var fresh = messageRepo.searchImageMessages(groupId, sessionId, null, "", ref.messageId(), "", true, requestTime, 0, 1);
            if (!fresh.isSuccess() || fresh.getData() == null || fresh.getData().isEmpty()
                    || references(fresh.getData().get(0)).stream().noneMatch(ref::equals))
                throw new IllegalArgumentException("图片 " + id + " 已不可用或内容改变，请重新查询");
            if (requiresConfirmation(ref) && !confirmedIds.contains(id))
                throw new IllegalArgumentException("图片 " + id + " 超过五分钟或时间未知，必须先询问用户并取得确认");
            if (ref.source().isBlank()) throw new IllegalArgumentException("图片 " + id + " 没有可读取原图，请用户重发");
            selected.add(ref);
        }
        List<String> inputs = new ArrayList<>();
        for (ImageReference ref : selected) inputs.add(service.loadReferenceImage(ref.source(), ref.generated()));
        return inputs;
    }

    private boolean requiresConfirmation(ImageReference ref) {
        return ref.sentAt() == null || ref.sentAt().isBefore(requestTime.minusMinutes(5));
    }

    private List<ImageReference> references(Map<String, Object> row) {
        LinkedHashMap<String, String> images = new LinkedHashMap<>();
        String legacy = value(row.get("image_data"));
        JsonNode descriptions = parse(legacy);
        if (descriptions.isArray()) for (JsonNode image : descriptions)
            images.put(image.path("url").asText(""), image.path("desc").asText(""));
        else if (!legacy.isBlank() && legacy.contains("|")) {
            String[] parts = legacy.split("\\|", 2);
            images.put(parts[0], parts[1]);
        }
        // Ordered raw segments include pictures even when Vision did not produce a description.
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        JsonNode segments = parse(value(row.get("raw_segments_json")));
        if (segments.isArray()) for (JsonNode part : segments) {
            if (!"image".equals(part.path("type").asText())) continue;
            String source = part.path("data").path("url").asText("");
            if (source.isBlank()) {
                String file = part.path("data").path("file").asText("");
                if (file.startsWith("https://") || file.startsWith("http://")) source = file;
            }
            // Preserve distinct images without URLs rather than losing ambiguity.
            String key = source.isBlank() ? "unavailable:" + ordered.size() : source;
            ordered.put(key, images.getOrDefault(source, ""));
        }
        images.forEach(ordered::putIfAbsent);
        boolean generated = "true".equalsIgnoreCase(value(row.get("is_robot_reply"))) || "1".equals(value(row.get("is_robot_reply")));
        if (generated) {
            var matcher = CQ_IMAGE.matcher(value(row.get("content")));
            while (matcher.find()) ordered.putIfAbsent(matcher.group(1).replace("&#44;", ",").replace("&#91;", "[")
                    .replace("&#93;", "]").replace("&amp;", "&"), "糖果熊之前发送的图片");
        }
        List<ImageReference> refs = new ArrayList<>();
        long messageId = number(row.get("id"), 0);
        LocalDateTime time = row.get("created_at") instanceof Timestamp ts ? ts.toLocalDateTime()
                : row.get("created_at") instanceof LocalDateTime dt ? dt : null;
        int index = 0;
        for (var image : ordered.entrySet()) {
            String desc = image.getValue().isBlank() ? value(row.get("content")) : image.getValue();
            // CQ paths and large text are not useful image descriptions.
            desc = CQ_IMAGE.matcher(desc).replaceAll("[图片]");
            if (desc.length() > 600) desc = desc.substring(0, 600) + "…";
            refs.add(new ImageReference(messageId + ":" + (++index), messageId, time,
                    image.getKey().startsWith("unavailable:") ? "" : image.getKey(), desc, generated));
        }
        return refs;
    }

    private static JsonNode parse(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            return node == null ? JSON.createArrayNode() : node;
        } catch (Exception ignored) { return JSON.createArrayNode(); }
    }
    private static String value(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static long number(Object value, long fallback) {
        try { return Long.parseLong(value(value)); } catch (Exception ignored) { return fallback; }
    }
    private record ImageReference(String id, long messageId, LocalDateTime sentAt, String source, String description, boolean generated) {}
}
