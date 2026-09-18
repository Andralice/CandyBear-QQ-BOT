package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Minimal client for Agnes' free Flash image and video models. */
public final class AgnesMediaService implements AutoCloseable {
    public static final String IMAGE_MODEL = "agnes-image-2.5-flash";
    public static final String VIDEO_MODEL = "agnes-video-2.5-flash";

    private static final Logger logger = LoggerFactory.getLogger(AgnesMediaService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> IMAGE_SIZES = Set.of("1K", "2K", "3K", "4K");
    private static final Set<String> RATIOS = Set.of("1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9");
    private static final Set<String> EXPLICIT_STYLE_MARKERS = Set.of(
            "卡通", "动漫", "二次元", "漫画", "插画", "水彩", "油画", "素描", "像素", "q版", "玩偶", "黏土",
            "国风", "水墨", "赛博朋克", "写实", "摄影", "真实", "电影感", "anime", "cartoon", "illustration",
            "watercolor", "oil painting", "sketch", "pixel art", "chibi", "clay", "photoreal", "realistic",
            "live-action", "live action", "cinematic realism");
    private static final String DEFAULT_REALISTIC_STYLE =
            "电影级写实摄影风格（cinematic photorealism, live-action photography），自然真实的皮肤、毛发、织物和材质纹理，"
                    + "符合物理规律的光线与透视，真实世界质感，35mm镜头，自然景深，丰富但不夸张的细节，"
                    + "cohesive color palette，限制为2到4个相互协调的主色，controlled saturation，统一白平衡和色彩分级，"
                    + "high-fidelity photographic detail，细腻微观纹理，自然微对比，真实材质响应；"
                    + "除非用户明确要求其他画风，不使用无必要的彩虹色块、塑料质感、过度磨皮、CGI渲染或扁平插画风格。";
    private static final int MAX_IMAGE_BYTES = 100 * 1024 * 1024;
    private static final int MAX_VIDEO_BYTES = 200 * 1024 * 1024;

    private final boolean enabled;
    private final String apiKey;
    private final String baseUrl;
    private final String videoRetrieveUrl;
    private final String pricingUrl;
    private final Path outputDir;
    private final int requestTimeoutMs;
    private final int videoTimeoutMs;
    private final int pollIntervalMs;
    private final boolean verifyFreePricing;
    private final HttpClient httpClient;
    private final ExecutorService pollExecutor;
    private final ExecutorService videoExecutor = Executors.newSingleThreadExecutor(daemonThreadFactory());
    private final Object videoQueueLock = new Object();
    private int activeVideoJobs;
    private int waitingVideoJobs;

    public AgnesMediaService() {
        this(
                BotConfig.isAgnesEnabled(),
                BotConfig.getAgnesApiKey(),
                BotConfig.getAgnesBaseUrl(),
                BotConfig.getAgnesVideoRetrieveUrl(),
                BotConfig.getAgnesPricingUrl(),
                BotConfig.getAgnesOutputDir(),
                BotConfig.getAgnesRequestTimeoutMs(),
                BotConfig.getAgnesVideoTimeoutMs(),
                BotConfig.getAgnesPollIntervalMs(),
                BotConfig.isAgnesVerifyFreePricing(),
                defaultHttpClient(),
                Executors.newCachedThreadPool(daemonThreadFactory())
        );
    }

    AgnesMediaService(boolean enabled, String apiKey, String baseUrl, String videoRetrieveUrl,
                      String pricingUrl, String outputDir, int requestTimeoutMs, int videoTimeoutMs,
                      int pollIntervalMs, boolean verifyFreePricing, HttpClient httpClient,
                      ExecutorService pollExecutor) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.videoRetrieveUrl = stripTrailingSlash(videoRetrieveUrl);
        this.pricingUrl = pricingUrl == null ? "" : pricingUrl.trim();
        this.outputDir = Path.of(outputDir == null || outputDir.isBlank() ? "generated-media" : outputDir)
                .toAbsolutePath().normalize();
        this.requestTimeoutMs = Math.max(1_000, requestTimeoutMs);
        this.videoTimeoutMs = Math.max(1_000, videoTimeoutMs);
        this.pollIntervalMs = Math.max(100, pollIntervalMs);
        this.verifyFreePricing = verifyFreePricing;
        this.httpClient = httpClient;
        this.pollExecutor = pollExecutor;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isConfigured() {
        return enabled && !apiKey.isBlank();
    }

    public List<GeneratedMedia> generateImage(String prompt, String size, String ratio,
                                               List<String> imageUrls) throws AgnesMediaException {
        requireConfigured();
        requirePrompt(prompt);
        String resolvedSize = size == null || size.isBlank() ? "1K" : size.trim().toUpperCase(Locale.ROOT);
        String resolvedRatio = ratio == null || ratio.isBlank() ? "1:1" : ratio.trim();
        if (!IMAGE_SIZES.contains(resolvedSize)) {
            throw new AgnesMediaException("图片尺寸只能是 1K、2K、3K 或 4K");
        }
        requireRatio(resolvedRatio);

        verifyCurrentFree(IMAGE_MODEL);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", IMAGE_MODEL);
        body.put("prompt", imageUrls == null || imageUrls.isEmpty()
                ? optimizeImagePrompt(prompt) : prompt.trim());
        body.put("size", resolvedSize);
        body.put("ratio", resolvedRatio);
        body.put("n", 1);
        ObjectNode extraBody = body.putObject("extra_body");
        extraBody.put("response_format", "url");
        if (imageUrls != null && !imageUrls.isEmpty()) {
            ArrayNode images = extraBody.putArray("image");
            for (String imageUrl : imageUrls) {
                requireImageInput(imageUrl);
                images.add(imageUrl.trim());
            }
        }

        JsonNode response = requestJson(baseUrl + "/images/generations", "POST", body);
        JsonNode data = response.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new AgnesMediaException("图片接口没有返回可下载的结果");
        }

        List<GeneratedMedia> results = new ArrayList<>();
        for (JsonNode item : data) {
            String url = textOrNull(item, "url");
            if (url != null) {
                results.add(new GeneratedMedia(download(url, "agnes-image", ".png", MAX_IMAGE_BYTES), "image"));
                continue;
            }
            String base64 = textOrNull(item, "b64_json");
            if (base64 != null) {
                results.add(new GeneratedMedia(saveBase64(base64, "agnes-image", ".png", MAX_IMAGE_BYTES), "image"));
                continue;
            }
            throw new AgnesMediaException("图片结果既没有 URL，也没有 base64 数据");
        }
        return results;
    }

    /** Agnes has no separate realism switch; strengthen the default only for text-to-image. */
    static String optimizeImagePrompt(String prompt) {
        String trimmed = prompt == null ? "" : prompt.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        boolean hasExplicitStyle = EXPLICIT_STYLE_MARKERS.stream().anyMatch(lower::contains);
        return hasExplicitStyle ? trimmed : trimmed + "，" + DEFAULT_REALISTIC_STYLE;
    }

    public String submitVideo(VideoRequest request) throws AgnesMediaException {
        requireConfigured();
        if (request == null) throw new AgnesMediaException("缺少视频参数");
        requirePrompt(request.prompt());

        String mode = request.mode() == null || request.mode().isBlank()
                ? "text" : request.mode().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("text", "keyframe", "reference").contains(mode)) {
            throw new AgnesMediaException("视频模式只能是 text、keyframe 或 reference");
        }
        if (request.seconds() < 4 || request.seconds() > 12) {
            throw new AgnesMediaException("视频时长只能是 4 到 12 秒");
        }
        String aspectRatio = request.aspectRatio() == null || request.aspectRatio().isBlank()
                ? "16:9" : request.aspectRatio().trim();
        requireRatio(aspectRatio);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", VIDEO_MODEL);
        body.put("prompt", request.prompt().trim());
        body.put("seconds", String.valueOf(request.seconds()));
        body.put("mode", mode);
        body.put("size", "720P");
        body.put("aspect_ratio", aspectRatio);
        body.put("n", 1);

        if ("keyframe".equals(mode)) {
            if (isBlank(request.firstFrame()) && isBlank(request.lastFrame())) {
                throw new AgnesMediaException("keyframe 模式至少需要 first_frame 或 last_frame");
            }
            if (!isBlank(request.firstFrame())) {
                requirePublicHttps(request.firstFrame(), "first_frame");
                body.put("first_frame", request.firstFrame().trim());
            }
            if (!isBlank(request.lastFrame())) {
                requirePublicHttps(request.lastFrame(), "last_frame");
                body.put("last_frame", request.lastFrame().trim());
            }
        } else if ("reference".equals(mode)) {
            List<String> images = nonNullList(request.images());
            List<String> audios = nonNullList(request.audios());
            if (images.isEmpty() && audios.isEmpty()) {
                throw new AgnesMediaException("reference 模式至少需要 image 或 audio");
            }
            if (images.size() > 5 || audios.size() > 3) {
                throw new AgnesMediaException("reference 模式最多支持 5 张图片和 3 段音频");
            }
            if (!images.isEmpty()) {
                ArrayNode imageArray = body.putArray("images");
                for (String image : images) {
                    requirePublicHttps(image, "image");
                    imageArray.add(image.trim());
                }
            }
            if (!audios.isEmpty()) {
                ArrayNode audioArray = body.putArray("audios");
                for (String audio : audios) {
                    requirePublicHttps(audio, "audio");
                    audioArray.add(audio.trim());
                }
            }
        } else if (!isBlank(request.firstFrame()) || !isBlank(request.lastFrame())
                || !nonNullList(request.images()).isEmpty() || !nonNullList(request.audios()).isEmpty()) {
            throw new AgnesMediaException("text 模式不能带媒体参数");
        }

        verifyCurrentFree(VIDEO_MODEL);
        JsonNode response = requestJson(baseUrl + "/videos", "POST", body);
        String taskId = findTaskId(response);
        if (taskId == null) {
            throw new AgnesMediaException("视频接口没有返回任务 ID");
        }
        return taskId;
    }

    public CompletableFuture<GeneratedMedia> pollVideoAsync(String taskId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return pollVideo(taskId);
            } catch (AgnesMediaException e) {
                throw new CompletionException(e);
            }
        }, pollExecutor);
    }

    /** Serialize the whole video lifecycle so only one job uses the provider at a time. */
    public VideoQueueTicket enqueueVideo(VideoRequest request) {
        CompletableFuture<GeneratedMedia> completion = new CompletableFuture<>();
        int ahead;
        synchronized (videoQueueLock) {
            ahead = activeVideoJobs + waitingVideoJobs;
            waitingVideoJobs++;
            videoExecutor.execute(() -> {
                synchronized (videoQueueLock) {
                    waitingVideoJobs--;
                    activeVideoJobs++;
                }
                try {
                    String taskId = submitVideo(request);
                    logger.info("Agnes 视频任务已提交: taskId={}", taskId);
                    completion.complete(pollVideo(taskId));
                } catch (AgnesMediaException e) {
                    completion.completeExceptionally(e);
                } catch (Exception e) {
                    completion.completeExceptionally(new AgnesMediaException("视频任务处理失败", e));
                } finally {
                    synchronized (videoQueueLock) {
                        activeVideoJobs--;
                    }
                }
            });
        }
        return new VideoQueueTicket(ahead, completion);
    }

    public GeneratedMedia pollVideo(String taskId) throws AgnesMediaException {
        if (taskId == null || taskId.isBlank()) throw new AgnesMediaException("缺少视频任务 ID");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(videoTimeoutMs);
        while (true) {
            String query = "video_id=" + URLEncoder.encode(taskId, StandardCharsets.UTF_8)
                    + "&model_name=" + URLEncoder.encode(VIDEO_MODEL, StandardCharsets.UTF_8);
            String separator = videoRetrieveUrl.contains("?") ? "&" : "?";
            JsonNode result;
            try {
                result = requestJson(videoRetrieveUrl + separator + query, "GET", null);
            } catch (AgnesMediaException e) {
                if (!isTransientVideoPollFailure(e)) throw e;
                logger.warn("Agnes 视频任务查询暂时失败，等待后继续查询: taskId={}, reason={}",
                        taskId, e.getMessage());
                waitForNextVideoPoll(deadline, taskId);
                continue;
            }
            String status = result.path("status").asText(result.path("state").asText(""))
                    .toLowerCase(Locale.ROOT);
            if (Set.of("failed", "error", "cancelled", "canceled").contains(status)) {
                throw new AgnesMediaException("视频任务 " + taskId + " 失败");
            }

            String mediaUrl = findFirstUrl(result);
            if (mediaUrl != null) {
                return new GeneratedMedia(download(mediaUrl, "agnes-video-" + safeFilePart(taskId), ".mp4",
                        MAX_VIDEO_BYTES), "video");
            }
            if (Set.of("completed", "succeeded", "success", "done").contains(status)) {
                throw new AgnesMediaException("视频任务 " + taskId + " 已完成但没有媒体 URL");
            }
            if (System.nanoTime() >= deadline) {
                throw new AgnesMediaException("视频任务 " + taskId + " 仍在处理中，未重复提交");
            }
            waitForNextVideoPoll(deadline, taskId);
        }
    }

    private void waitForNextVideoPoll(long deadline, String taskId) throws AgnesMediaException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new AgnesMediaException("视频任务 " + taskId + " 仍在处理中，未重复提交");
        }
        long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        long delayMs = Math.min(pollIntervalMs, remainingMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgnesMediaException("视频轮询被中断，任务 " + taskId + " 未被取消", e);
        }
    }

    private static boolean isTransientVideoPollFailure(AgnesMediaException error) {
        String message = error == null ? "" : error.getMessage();
        return message != null && message.matches(".*HTTP (408|425|429|5\\d\\d).*" );
    }

    @Override
    public void close() {
        pollExecutor.shutdownNow();
        videoExecutor.shutdownNow();
    }

    private void requireConfigured() throws AgnesMediaException {
        if (!enabled) throw new AgnesMediaException("Agnes 媒体功能未启用，请设置 AGNES_ENABLED=true");
        if (apiKey.isBlank()) throw new AgnesMediaException("Agnes 未配置 AGNES_API_KEY");
        if (baseUrl.isBlank() || videoRetrieveUrl.isBlank()) {
            throw new AgnesMediaException("Agnes API 地址未配置");
        }
    }

    private void verifyCurrentFree(String model) throws AgnesMediaException {
        if (!verifyFreePricing) return;
        if (pricingUrl.isBlank()) throw new AgnesMediaException("无法确认 Agnes 当前价格，未发送请求");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pricingUrl))
                    .header("Accept", "text/plain,text/markdown,text/html;q=0.9,*/*;q=0.8")
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgnesMediaException("无法确认 Agnes 当前价格，未发送请求");
            }
            String text = response.body() == null ? "" : response.body().replace("\\$", "$");
            String lower = text.toLowerCase(Locale.ROOT);
            int start = lower.indexOf(model.toLowerCase(Locale.ROOT));
            int end = start < 0 ? -1 : Math.min(lower.length(), start + 5_000);
            if (start < 0 || !lower.substring(start, end).contains("$0")) {
                throw new AgnesMediaException("官方价格未确认 " + model + " 仍为 $0，未发送请求");
            }
        } catch (AgnesMediaException e) {
            throw e;
        } catch (Exception e) {
            throw new AgnesMediaException("无法确认 Agnes 当前价格，未发送请求", e);
        }
    }

    private JsonNode requestJson(String url, String method, JsonNode body) throws AgnesMediaException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(requestTimeoutMs));
        if (body == null) {
            builder.GET();
        } else {
            try {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            } catch (IOException e) {
                throw new AgnesMediaException("Agnes 请求体编码失败", e);
            }
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgnesMediaException("Agnes API 请求失败（HTTP " + response.statusCode() + "）");
            }
            JsonNode result = MAPPER.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw new AgnesMediaException("Agnes 返回了无法识别的 JSON");
            }
            return result;
        } catch (AgnesMediaException e) {
            throw e;
        } catch (Exception e) {
            throw new AgnesMediaException("Agnes API 请求失败: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Load a scoped chat reference; local files are restricted to our generated-media directory. */
    public String loadReferenceImage(String source, boolean generated) throws AgnesMediaException {
        try {
            byte[] bytes;
            if (!source.startsWith("https://") && !source.startsWith("http://")) {
                if (!generated) throw new AgnesMediaException("参考图片不是可下载地址");
                Path file = (source.startsWith("file:") ? Path.of(URI.create(source)) : Path.of(source)).toRealPath();
                if (!file.startsWith(outputDir.toRealPath()) || !Files.isRegularFile(file))
                    throw new AgnesMediaException("参考图片不在糖果熊生成图片目录内");
                try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_IMAGE_BYTES + 1); }
            } else {
                URI uri = URI.create(source);
                if (uri.getUserInfo() != null || uri.getHost() == null) throw new AgnesMediaException("参考图片地址无效");
                for (var address : java.net.InetAddress.getAllByName(uri.getHost())) {
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                            || address.isSiteLocalAddress() || address.isMulticastAddress()
                            || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc))
                        throw new AgnesMediaException("参考图片地址必须可从公网访问");
                }
                // Do not follow redirects to local or authenticated endpoints.
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "Mozilla/5.0").GET().build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var input = response.body()) {
                    if (response.statusCode() != 200) throw new AgnesMediaException("读取参考图片失败（HTTP " + response.statusCode() + "），请重新发送原图");
                    if (response.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_IMAGE_BYTES)
                        throw new AgnesMediaException("参考图片超过大小限制");
                    bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
                }
            }
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new AgnesMediaException("参考图片为空或超过大小限制");
            String mime;
            if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') mime = "image/png";
            else if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) mime = "image/jpeg";
            else if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') mime = "image/gif";
            else if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') mime = "image/webp";
            else throw new AgnesMediaException("参考文件不是支持的 PNG/JPEG/GIF/WebP 图片，请重新发送原图");
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (AgnesMediaException e) { throw e; }
        catch (Exception e) { throw new AgnesMediaException("读取参考图片失败，请重新发送原图", e); }
    }

    private Path download(String url, String prefix, String suffix, int maxBytes) throws AgnesMediaException {
        requireHttpUrl(url, "provider media URL");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "*/*")
                .timeout(Duration.ofMillis(Math.max(requestTimeoutMs, 30_000)))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgnesMediaException("下载 Agnes 媒体失败（HTTP " + response.statusCode() + "）");
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxBytes) throw new AgnesMediaException("Agnes 媒体超过大小限制");
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) throw new AgnesMediaException("Agnes 返回了空媒体");
            if (bytes.length > maxBytes) throw new AgnesMediaException("Agnes 媒体超过大小限制");
            return saveBytes(bytes, prefix, suffix);
        } catch (AgnesMediaException e) {
            throw e;
        } catch (Exception e) {
            throw new AgnesMediaException("下载 Agnes 媒体失败", e);
        }
    }

    private Path saveBase64(String value, String prefix, String suffix, int maxBytes) throws AgnesMediaException {
        String encoded = value;
        int comma = encoded.indexOf(',');
        if (encoded.startsWith("data:") && comma >= 0) encoded = encoded.substring(comma + 1);
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0) throw new AgnesMediaException("Agnes 返回了空媒体");
            if (bytes.length > maxBytes) throw new AgnesMediaException("Agnes 媒体超过大小限制");
            return saveBytes(bytes, prefix, suffix);
        } catch (IllegalArgumentException e) {
            throw new AgnesMediaException("Agnes 返回了无效的 base64 媒体", e);
        }
    }

    private Path saveBytes(byte[] bytes, String prefix, String suffix) throws AgnesMediaException {
        Path temp = outputDir.resolve("." + prefix + "-" + UUID.randomUUID() + ".part");
        Path target = outputDir.resolve(prefix + "-" + UUID.randomUUID() + suffix);
        try {
            Files.createDirectories(outputDir);
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (Files.size(temp) == 0) throw new AgnesMediaException("保存 Agnes 媒体失败：文件为空");
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
            return target;
        } catch (AgnesMediaException e) {
            throw e;
        } catch (Exception e) {
            throw new AgnesMediaException("保存 Agnes 媒体失败", e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                logger.debug("清理 Agnes 临时文件失败: {}", temp);
            }
        }
    }

    private static String findTaskId(JsonNode node) {
        if (node == null) return null;
        for (String key : List.of("video_id", "id", "task_id")) {
            JsonNode value = node.get(key);
            if (value != null && (value.isTextual() || value.isIntegralNumber())) return value.asText();
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                String found = findTaskId(fields.next().getValue());
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findTaskId(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String findFirstUrl(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            for (String key : List.of("video_url", "url", "download_url", "file_url")) {
                String value = textOrNull(node, key);
                if (value != null && isHttpUrl(value)) return value;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String found = findFirstUrl(fields.next().getValue());
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findFirstUrl(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static void requirePrompt(String prompt) throws AgnesMediaException {
        if (prompt == null || prompt.isBlank()) throw new AgnesMediaException("提示词不能为空");
        if (prompt.trim().length() > 4_000) throw new AgnesMediaException("提示词不能超过 4000 个字符");
    }

    private static void requireRatio(String ratio) throws AgnesMediaException {
        if (!RATIOS.contains(ratio)) throw new AgnesMediaException("不支持的画面比例: " + ratio);
    }

    private static void requireImageInput(String value) throws AgnesMediaException {
        if (isBlank(value) || !(value.trim().startsWith("https://") || value.trim().startsWith("data:image/"))) {
            throw new AgnesMediaException("图片参考必须是 public HTTPS URL 或 image data URI");
        }
    }

    private static void requirePublicHttps(String value, String name) throws AgnesMediaException {
        if (isBlank(value) || !value.trim().startsWith("https://")) {
            throw new AgnesMediaException(name + " 必须是 public HTTPS URL");
        }
    }

    private static void requireHttpUrl(String value, String name) throws AgnesMediaException {
        if (!isHttpUrl(value)) throw new AgnesMediaException(name + " 不是有效的 HTTP URL");
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> nonNullList(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("/+$", "");
    }

    private static String safeFilePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "Agnes-Media-Poller");
            thread.setDaemon(true);
            return thread;
        };
    }

    public record GeneratedMedia(Path path, String kind) {}

    public record VideoQueueTicket(int queuedAhead, CompletableFuture<GeneratedMedia> future) {}

    public record VideoRequest(String prompt, String mode, int seconds, String aspectRatio,
                               String firstFrame, String lastFrame, List<String> images,
                               List<String> audios) {}

    public static class AgnesMediaException extends Exception {
        public AgnesMediaException(String message) {
            super(message);
        }

        public AgnesMediaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
