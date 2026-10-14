package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.BotConfig;
import com.start.repository.MessageRepository;
import com.start.service.AgnesMediaService;
import com.start.vision.ImageUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Conversation-scoped image lookup; raw images enter the model only after explicit selection. */
public class QueryImagesTool implements Tool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CQ_IMAGE = Pattern.compile("\\[CQ:image,file=([^,\\]]+)[^\\]]*]");
    private static final long DEFAULT_VISION_WAIT_MS = 1800L;
    private final MessageRepository messageRepo;
    private final String groupId, sessionId, callerId;
    private final LocalDateTime requestTime;
    private final AgnesMediaService mediaService;
    private final Map<String, ImageReference> discovered = new HashMap<>();

    public QueryImagesTool(MessageRepository repo, String groupId) {
        this(repo, groupId, null, null, LocalDateTime.now());
    }

    public QueryImagesTool(MessageRepository repo, String groupId, String sessionId, String callerId) {
        this(repo, groupId, sessionId, callerId, LocalDateTime.now(), null);
    }

    QueryImagesTool(MessageRepository repo, String groupId, String sessionId, String callerId, LocalDateTime time) {
        this(repo, groupId, sessionId, callerId, time, null);
    }

    public QueryImagesTool(MessageRepository repo, String groupId, String sessionId, String callerId,
                           AgnesMediaService mediaService) {
        this(repo, groupId, sessionId, callerId, LocalDateTime.now(), mediaService);
    }

    QueryImagesTool(MessageRepository repo, String groupId, String sessionId, String callerId,
                    LocalDateTime time, AgnesMediaService mediaService) {
        this.messageRepo = repo;
        this.groupId = groupId;
        this.sessionId = sessionId;
        this.callerId = callerId;
        this.requestTime = time;
        this.mediaService = mediaService;
    }

    @Override public String getName() { return "query_past_images"; }

    @Override public String getDescription() {
        return "自主查找当前会话图片。可无关键词搜索，默认当前用户五分钟内的图；可按QQ、关键词、回复消息ID或游标定位。"
                + "查旧图用 include_older=true；查糖果熊生成的图必须用 user_id=candybear。返回每张图的 image_id、消息内 image_index、发送者、完整时间、"
                + "描述、vision_status（PENDING/PROCESSING/SUCCEEDED/FAILED 等）和原图来源状态，供主模型判断是否要等待及供 generate_image 的 reference_image_ids 使用。没有明确发送者时，按消息时间、引用关系和 image_index 判断，必要时用 user_id=all；"
                + "多候选先判断指代，再次调用时将选中的 image_id 放入 image_ids，主模型会直接收到这些原图；超过五分钟的图片必须先询问用户并在 confirmed_image_ids 中确认。";
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
        p.put("wait_for_vision", Map.of("type", "boolean", "description", "图片会影响当前回答时默认等待识图短暂完成；明确不需要画面内容时可传false"));
        p.put("vision_wait_ms", Map.of("type", "integer", "description", "最多等待识图毫秒数，默认1800，最多4000"));
        p.put("image_ids", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 3,
                "description", "从此前查询结果中选择要让主模型直接查看的图片 image_id，最多3张"));
        p.put("confirmed_image_ids", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 3,
                "description", "用户明确确认可查看的较早图片 image_id"));
        return Map.of("type", "object", "properties", p);
    }

    @Override public ExecutionResult executeForModel(Map<String, Object> args) {
        List<String> ids = stringList(args.get("image_ids"));
        if (ids.isEmpty()) return Tool.super.executeForModel(args);
        if (ids.size() > 3) return new ExecutionResult("一次最多查看3张原图", List.of());
        if (!BotConfig.isBaiLianMultimodalEnabled()) {
            return new ExecutionResult("主模型多模态输入未启用，原图没有发送；请检查 BAILIAN_MULTIMODAL_ENABLED。", List.of());
        }
        if (mediaService == null) return new ExecutionResult("当前图片查询未配置原图读取服务", List.of());
        try {
            List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(ids));
            List<String> confirmed = stringList(args.get("confirmed_image_ids"));
            List<String> dataUris = resolveInputs(uniqueIds, confirmed, mediaService);
            List<ImageAttachment> attachments = new ArrayList<>();
            for (int i = 0; i < dataUris.size(); i++) {
                String id = uniqueIds.get(i);
                for (String prepared : ImageUtils.prepareMultimodalDataUris(List.of(dataUris.get(i)))) {
                    attachments.add(new ImageAttachment(id, prepared));
                }
            }
            if (attachments.isEmpty()) return new ExecutionResult("所选图片没有可发送的原图", List.of());
            return new ExecutionResult("已按 image_id 选中原图，图像内容已附在本次工具结果中；请直接查看后回答："
                    + String.join("、", uniqueIds), attachments);
        } catch (Exception e) {
            return new ExecutionResult("读取所选原图失败，请确认图片仍可用后重试（"
                    + e.getClass().getSimpleName() + "）", List.of());
        }
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
            boolean waitForVision = !args.containsKey("wait_for_vision")
                    || Boolean.parseBoolean(value(args.get("wait_for_vision")));
            long waitMs = Math.max(0, Math.min(4000, number(args.get("vision_wait_ms"), DEFAULT_VISION_WAIT_MS)));
            if (waitForVision && hasPendingVision(result.getData()) && waitMs > 0) {
                long deadline = System.currentTimeMillis() + waitMs;
                do {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    Thread.sleep(Math.min(200, remaining));
                    result = messageRepo.searchImageMessages(groupId, sessionId, sender, value(args.get("keyword")),
                            number(args.get("message_id"), 0), value(args.get("provider_message_id")),
                            Boolean.parseBoolean(value(args.get("include_older"))), requestTime,
                            number(args.get("before_id"), 0), (int) Math.max(1, Math.min(10, number(args.get("limit"), 5))));
                } while (result.isSuccess() && result.getData() != null && hasPendingVision(result.getData()));
            }
            List<Map<String, Object>> items = new ArrayList<>();
            long cursor = 0;
            for (Map<String, Object> row : result.getData()) {
                cursor = number(row.get("id"), 0);
                for (ImageReference ref : references(row)) {
                    discovered.put(ref.id(), ref);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("image_id", ref.id());
                    item.put("message_id", ref.messageId());
                    item.put("image_index", ref.imageIndex());
                    item.put("provider_message_id", row.get("provider_message_id"));
                    item.put("user_id", row.get("user_id"));
                    item.put("sender", "candybear".equals(String.valueOf(row.get("user_id"))) ? "糖果熊"
                            : String.valueOf(row.get("user_id")));
                    item.put("sent_at", ref.sentAt() == null ? "UNKNOWN" : ref.sentAt().toString());
                    item.put("requires_confirmation", requiresConfirmation(ref));
                    item.put("description", ref.description());
                    item.put("vision_status", visionStatus(row, ref.imageIndex()));
                    item.put("source_status", hasUsableSource(ref) ? "RECORDED_NOT_DOWNLOADED" : "UNAVAILABLE");
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
            if (!hasUsableSource(ref)) throw new IllegalArgumentException("图片 " + id + " 没有可读取原图，请用户重发");
            selected.add(ref);
        }
        List<String> inputs = new ArrayList<>();
        for (ImageReference ref : selected) inputs.add(service.loadReferenceImage(ref.source(), ref.generated()));
        return inputs;
    }

    private boolean requiresConfirmation(ImageReference ref) {
        return ref.sentAt() == null || ref.sentAt().isBefore(requestTime.minusMinutes(5));
    }

    private boolean hasUsableSource(ImageReference ref) {
        if (ref == null || ref.source().isBlank()) return false;
        return isHttpUrl(ref.source()) || ref.generated();
    }

    private List<ImageReference> references(Map<String, Object> row) {
        List<ImageCandidate> descriptionEntries = new ArrayList<>();
        String legacy = value(row.get("image_data"));
        JsonNode descriptionJson = parse(legacy);
        if (descriptionJson.isArray()) for (JsonNode image : descriptionJson) {
            String source = image.path("url").asText("");
            if (source.isBlank()) {
                String file = image.path("file").asText("");
                if (isHttpUrl(file)) source = file;
            }
            descriptionEntries.add(new ImageCandidate(source, image.path("desc").asText("")));
        }
        else if (!legacy.isBlank() && legacy.contains("|")) {
            String[] parts = legacy.split("\\|", 2);
            descriptionEntries.add(new ImageCandidate(parts[0], parts[1]));
        }
        // Ordered raw segments include pictures even when Vision did not produce a description.
        List<ImageCandidate> ordered = new ArrayList<>();
        JsonNode segments = parse(value(row.get("raw_segments_json")));
        if (segments.isArray()) {
            int rawIndex = 0;
            for (JsonNode part : segments) {
            if (!"image".equals(part.path("type").asText())) continue;
            String source = part.path("data").path("url").asText("");
            if (source.isBlank()) {
                String file = part.path("data").path("file").asText("");
                if (file.startsWith("https://") || file.startsWith("http://")) source = file;
            }
            ImageCandidate described = rawIndex < descriptionEntries.size() ? descriptionEntries.get(rawIndex) : null;
            if (source.isBlank() && described != null) source = described.source();
            ordered.add(new ImageCandidate(source, described == null ? "" : described.description()));
            rawIndex++;
            }
        }
        // Older rows may only have image_data. Keep those entries in their stored order.
        for (int i = ordered.size(); i < descriptionEntries.size(); i++) ordered.add(descriptionEntries.get(i));
        boolean generated = "true".equalsIgnoreCase(value(row.get("is_robot_reply"))) || "1".equals(value(row.get("is_robot_reply")));
        if (generated) {
            var matcher = CQ_IMAGE.matcher(value(row.get("content")));
            while (matcher.find()) ordered.add(new ImageCandidate(matcher.group(1).replace("&#44;", ",").replace("&#91;", "[")
                    .replace("&#93;", "]").replace("&amp;", "&"), "糖果熊之前发送的图片"));
        }
        List<ImageReference> refs = new ArrayList<>();
        long messageId = number(row.get("id"), 0);
        LocalDateTime time = row.get("created_at") instanceof Timestamp ts ? ts.toLocalDateTime()
                : row.get("created_at") instanceof LocalDateTime dt ? dt : null;
        int index = 0;
        for (ImageCandidate image : ordered) {
            // The message text is not a description of the attached picture.
            // Falling back to it made "你看得到吗" look like visual evidence.
            String desc = image.description();
            // CQ paths and large text are not useful image descriptions.
            desc = CQ_IMAGE.matcher(desc).replaceAll("[图片]");
            if (desc.length() > 600) desc = desc.substring(0, 600) + "…";
            refs.add(new ImageReference(messageId + ":" + (++index), messageId, time,
                    image.source(), desc, generated, index));
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

    private static String visionStatus(Map<String, Object> row, int imageIndex) {
        String encoded = value(row.get("image_vision_status"));
        if (!encoded.isBlank()) {
            for (String entry : encoded.split(",")) {
                String[] pair = entry.split(":", 2);
                if (pair.length == 2 && number(pair[0], -1) == imageIndex) return pair[1];
            }
        }
        String legacy = value(row.get("availability_status"));
        return legacy.isBlank() ? "UNKNOWN" : legacy;
    }

    private static boolean hasPendingVision(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            String encoded = value(row.get("image_vision_status"));
            if (encoded.isBlank()) continue;
            for (String entry : encoded.split(",")) {
                String[] pair = entry.split(":", 2);
                if (pair.length == 2 && ("PENDING".equalsIgnoreCase(pair[1])
                        || "PROCESSING".equalsIgnoreCase(pair[1])
                        || "RETRY".equalsIgnoreCase(pair[1]))) return true;
            }
        }
        return false;
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) return false;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    private static long number(Object value, long fallback) {
        try { return Long.parseLong(value(value)); } catch (Exception ignored) { return fallback; }
    }
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String item = value(value);
            if (!item.isBlank()) result.add(item);
        }
        return result;
    }
    private record ImageCandidate(String source, String description) {}
    private record ImageReference(String id, long messageId, LocalDateTime sentAt, String source,
                                  String description, boolean generated, int imageIndex) {}
}
