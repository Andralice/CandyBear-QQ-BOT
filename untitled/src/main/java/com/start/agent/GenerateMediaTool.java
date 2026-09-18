package com.start.agent;

import com.start.Main;
import com.start.service.AgnesMediaService;
import com.start.service.ToolAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** LLM tools for sending Agnes-generated images or videos to the current chat. */
public final class GenerateMediaTool implements Tool {
    public enum Kind { IMAGE, VIDEO }

    private static final Logger logger = LoggerFactory.getLogger(GenerateMediaTool.class);
    private final Main bot;
    private final AgnesMediaService mediaService;
    private final String groupId;
    private final String userId;
    private final String sessionId;
    private final Kind kind;
    private final QueryImagesTool imageQuery;
    private boolean imageRequirementsReviewed;
    public static final String REQUIREMENTS_REQUIRED = "IMAGE_REQUIREMENTS_REQUIRED";
    public static final String MEDIA_HANDLED = "MEDIA_HANDLED:";

    public GenerateMediaTool(Main bot, AgnesMediaService mediaService, String groupId,
                             String userId, String sessionId, Kind kind) {
        this(bot, mediaService, groupId, userId, sessionId, kind, null);
    }

    public GenerateMediaTool(Main bot, AgnesMediaService mediaService, String groupId,
                             String userId, String sessionId, Kind kind, QueryImagesTool imageQuery) {
        this.bot = bot;
        this.mediaService = mediaService;
        this.groupId = groupId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.kind = kind;
        this.imageQuery = imageQuery;
    }

    public String invocationRequirements() {
        try (var input = GenerateMediaTool.class.getResourceAsStream("/image-generation-rules.md")) {
            if (input == null) throw new IllegalStateException("缺少生图调用规范，不能继续生成");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new IllegalStateException("无法读取生图调用规范", e); }
    }

    /** Returns the one-time image rules and marks them as reviewed for native protocols. */
    public String consumeImageRequirements() {
        if (kind != Kind.IMAGE || imageRequirementsReviewed) return "";
        String requirements = invocationRequirements();
        imageRequirementsReviewed = true;
        return requirements;
    }

    /** Called only after the complete tool-call batch and system injection, before the next LLM round. */
    public void injectImageRequirements(List<Map<String, Object>> messages) {
        String requirements = consumeImageRequirements();
        if (!requirements.isBlank()) {
            messages.add(Map.of("role", "system", "content", requirements));
        }
    }

    @Override
    public String getName() {
        return kind == Kind.IMAGE ? "generate_image" : "generate_video";
    }

    @Override
    public String getDescription() {
        if (kind == Kind.IMAGE) {
            return "生成、参考图片创作或修改图片，完成后自动发送。先遵守系统注入的生图规范并查证；若尚未加载规范，首次调用只返回规范、不生成。"
                    + "先 query_message_context 汇总此前要求；自主 query_past_images 查原图和回复目标，人物按@QQ/别名查 query_user_profile。"
                    + "多图指代不明须追问，五分钟前图片须用户确认。参考/修改必须填已查询的 reference_image_ids 和 selection_reason，"
                    + "修改需明确改变和保留区域；未指定画风时从电影写实、纪实街拍、商业人像、产品/场景写实中选择一个并写入完整 prompt，"
                    + "明确指定的动漫/卡通/插画等画风必须保留；"
                    + "画风问题补具体提示词，必要时联网搜索。只描述图片不要调用。";
        }
        return "仅在用户明确要求生成视频、动画或短片时调用。视频固定 720P、4-12 秒、一次生成 1 个，"
                + "按队列串行处理，提交后后台每 60 秒轮询，完成后自动发送到当前聊天。若返回排队数量，要告诉用户前边还有几个视频正在生成。"
                + "mode=text 用文字生成；keyframe 需要公开 HTTPS 的 first_frame/last_frame；"
                + "reference 需要公开 HTTPS 的 images 或 audios。不要因为用户发来图片或让你描述图片就调用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        if (kind == Kind.IMAGE) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("prompt", Map.of("type", "string", "description", "图片生成提示词，描述主体、场景、动作、风格和构图"));
            properties.put("size", Map.of("type", "string", "description", "图片尺寸：1K、2K、3K 或 4K；默认 1K"));
            properties.put("ratio", Map.of("type", "string", "description", "画面比例，例如 1:1、16:9、9:16；默认 1:1"));
            properties.put("image_urls", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "仅用于用户明确提供的公开 HTTPS 参考图；聊天图片使用 reference_image_ids，不填Base64/本地路径"));
            properties.put("mode", Map.of("type", "string", "enum", List.of("text", "reference", "edit"),
                    "description", "文生图、参考图创作或修改已有图片；查阅规范后的调用必填"));
            properties.put("requirements_summary", Map.of("type", "string", "description", "查阅上下文后汇总当前及之前仍有效的用户要求，查阅规范后的调用必填"));
            properties.put("reference_image_ids", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "query_past_images 返回的 image_id，按 prompt 中引用次序排列，最多5张"));
            properties.put("confirmed_image_ids", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "超过五分钟/时间未知且用户已明确确认使用的具体 image_id，不得自行假定确认"));
            properties.put("selection_reason", Map.of("type", "string", "description", "参考/修改时必填：图片如何对应用户指代、回复关系，多图各自用途及旧图用户确认依据"));
            return Map.of("type", "object", "properties", properties, "required", List.of("prompt"));
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of("type", "string", "description", "视频提示词，描述主体、动作、镜头、节奏和风格"));
        properties.put("mode", Map.of("type", "string", "description", "text、keyframe 或 reference；默认 text"));
        properties.put("seconds", Map.of("type", "integer", "description", "视频时长，4 到 12 秒；默认 5"));
        properties.put("aspect_ratio", Map.of("type", "string", "description", "画面比例，例如 16:9、9:16；默认 16:9"));
        properties.put("first_frame", Map.of("type", "string", "description", "keyframe 模式的公开 HTTPS 首帧 URL"));
        properties.put("last_frame", Map.of("type", "string", "description", "keyframe 模式的公开 HTTPS 尾帧 URL"));
        properties.put("images", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "reference 模式的公开 HTTPS 图片 URL，最多 5 个"));
        properties.put("audios", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "reference 模式的公开 HTTPS 音频 URL，最多 3 个"));
        return Map.of("type", "object", "properties", properties, "required", List.of("prompt"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String accessError = checkAccess();
        if (accessError != null) return accessError;
        if (kind == Kind.IMAGE && !imageRequirementsReviewed)
            return REQUIREMENTS_REQUIRED + "\n" + invocationRequirements();
        try {
            return kind == Kind.IMAGE ? generateImage(args) : generateVideo(args);
        } catch (IllegalArgumentException e) {
            return kind == Kind.IMAGE
                    ? "画图前还缺信息：" + e.getMessage() + "。尚未开始画。"
                    : "视频生成前还缺信息：" + e.getMessage() + "。尚未开始生成。";
        } catch (AgnesMediaService.AgnesMediaException e) {
            logger.warn("Agnes {} 失败: {}", kind.name().toLowerCase(Locale.ROOT), e.getMessage());
            return (kind == Kind.IMAGE ? "画图" : "视频生成") + "失败：" + e.getMessage();
        } catch (Exception e) {
            logger.warn("Agnes {} 失败", kind.name().toLowerCase(Locale.ROOT), e);
            return (kind == Kind.IMAGE ? "画图" : "视频生成") + "失败：" + e.getClass().getSimpleName();
        }
    }

    private String generateImage(Map<String, Object> args) throws AgnesMediaService.AgnesMediaException {
        String prompt = stringArg(args, "prompt");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("缺少整理后的 prompt");
        String summary = stringArg(args, "requirements_summary");
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("必须先查询上下文并填写 requirements_summary");
        String mode = defaultString(stringArg(args, "mode"), "");
        if (!List.of("text", "reference", "edit").contains(mode)) throw new IllegalArgumentException("必须明确 mode=text/reference/edit");
        String size = stringArg(args, "size");
        String ratio = stringArg(args, "ratio");
        List<String> ids = stringListArg(args, "reference_image_ids");
        List<String> imageUrls = new ArrayList<>(stringListArg(args, "image_urls"));
        if (imageUrls.stream().anyMatch(url -> !url.startsWith("https://")))
            throw new IllegalArgumentException("image_urls 只接受公开HTTPS地址；聊天图片请先查询并用编号引用");
        if (ids.size() + imageUrls.size() > 5) throw new IllegalArgumentException("最多5张参考图");
        if ("text".equals(mode) && (!ids.isEmpty() || !imageUrls.isEmpty()))
            throw new IllegalArgumentException("已有参考图片，须使用reference或edit模式");
        if (!"text".equals(mode)) {
            if (ids.isEmpty() && imageUrls.isEmpty()) throw new IllegalArgumentException("参考或修改图片必须先定位原图，不能退回文生图");
            if (defaultString(stringArg(args, "selection_reason"), "").isBlank())
                throw new IllegalArgumentException("必须说明图片选择依据 selection_reason");
        }
        if (!ids.isEmpty()) {
            if (imageQuery == null) throw new IllegalArgumentException("当前缺少图片查询上下文");
            List<String> references = imageQuery.resolveInputs(ids, stringListArg(args, "confirmed_image_ids"), mediaService);
            references.addAll(imageUrls);
            imageUrls = references;
        }
        sendGenerationStatus(false, 0);
        List<AgnesMediaService.GeneratedMedia> results = mediaService.generateImage(prompt, size, ratio, imageUrls);
        int sent = 0;
        for (AgnesMediaService.GeneratedMedia media : results) {
            if (sendMedia(media)) sent++;
        }
        if (sent > 0) {
            return MEDIA_HANDLED + "已经画好并发送 " + sent + "/" + results.size() + " 张图";
        }
        return "图画好了，但发送失败";
    }

    private String generateVideo(Map<String, Object> args) throws AgnesMediaService.AgnesMediaException {
        String prompt = stringArg(args, "prompt");
        String mode = defaultString(stringArg(args, "mode"), "text");
        int seconds = intArg(args, "seconds", 5);
        String aspectRatio = defaultString(stringArg(args, "aspect_ratio"), "16:9");
        AgnesMediaService.VideoRequest request = new AgnesMediaService.VideoRequest(
                prompt, mode, seconds, aspectRatio,
                stringArg(args, "first_frame"), stringArg(args, "last_frame"),
                stringListArg(args, "images"), stringListArg(args, "audios"));
        AgnesMediaService.VideoQueueTicket ticket = mediaService.enqueueVideo(request);
        sendGenerationStatus(true, ticket.queuedAhead());
        ticket.future().whenComplete((media, error) -> {
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                logger.warn("Agnes 视频任务完成失败: reason={}", cause.getMessage());
                sendText("视频任务未完成：" + (cause.getMessage() == null ? "请稍后重试" : cause.getMessage()));
                return;
            }
            if (!sendMedia(media)) {
                logger.warn("Agnes 视频已下载但发送失败: path={}", media.path());
            }
        });
        return MEDIA_HANDLED + (ticket.queuedAhead() == 0
                ? "视频已开始生成，完成后会自动发送。"
                : "视频已加入队列，前面还有 " + ticket.queuedAhead() + " 个视频正在生成，完成后会自动发送。");
    }

    public static boolean isHandledResult(String result) {
        return result != null && result.startsWith(MEDIA_HANDLED);
    }

    private String checkAccess() {
        if (!mediaService.isEnabled()) return "Agnes 媒体功能未启用，请设置 AGNES_ENABLED=true";
        ToolAuthorizationService auth = ToolAuthorizationService.getInstance();
        if (auth != null) {
            if (auth.isBlacklistedPrivate(userId)) return "拒绝使用：当前账号已被限制使用媒体生成功能";
        }
        return null;
    }

    private boolean sendMedia(AgnesMediaService.GeneratedMedia media) {
        if (media == null || media.path() == null || bot == null) return false;
        Path path = media.path().toAbsolutePath().normalize();
        String cqType = "video".equals(media.kind()) ? "video" : "image";
        String message = "[CQ:" + cqType + ",file=" + path + "]";
        try {
            if (groupId != null && !groupId.isBlank()) {
                return bot.sendGroupReply(Long.parseLong(groupId), message, sessionId);
            }
            return bot.sendPrivateReply(Long.parseLong(userId), message, sessionId);
        } catch (Exception e) {
            logger.warn("发送 Agnes {} 媒体失败: {}", cqType, e.getMessage());
            return false;
        }
    }

    private void sendGenerationStatus(boolean video, int queuedAhead) {
        if (SendStatusTool.consumeRecent(groupId, userId)) return;
        String message;
        if (video && queuedAhead > 0) {
            message = "前面还有 " + queuedAhead + " 个视频，我排会儿队。";
        } else {
            message = video
                    ? "我开始做视频了，得等一两分钟。"
                    : "我开始画了，等我一会儿。";
        }
        if (!sendText(message)) {
            logger.warn("Agnes 生成状态消息发送失败");
        }
    }

    private boolean sendText(String message) {
        try {
            if (groupId != null && !groupId.isBlank()) {
                return bot.sendGroupReply(Long.parseLong(groupId), message, sessionId);
            } else {
                return bot.sendPrivateReply(Long.parseLong(userId), message, sessionId);
            }
        } catch (Exception e) {
            logger.warn("发送 Agnes 任务状态失败: {}", e.getMessage());
            return false;
        }
    }

    private static String stringArg(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intArg(Map<String, Object> args, String name, int fallback) {
        Object value = args == null ? null : args.get(name);
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // Service validation returns the user-facing error.
            }
        }
        return fallback;
    }

    private static List<String> stringListArg(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) return List.of();
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim());
            }
            return result;
        }
        String single = String.valueOf(value).trim();
        return single.isBlank() ? List.of() : Arrays.asList(single.split("\\s*,\\s*"));
    }
}
