package com.start.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.vision.ImageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表情包入库与检索服务（单例）。
 *
 * <p>职责：
 * <ul>
 *     <li>把群里疑似表情包的图片下载、存到本地、生成元数据并持久化到 {@code data/stickers.json}</li>
 *     <li>提供关键词检索 API 给 {@link com.start.agent.StickerTool}</li>
 *     <li>支持管理员通过 {@link com.start.agent.StickerAdminTool} 覆盖关键词</li>
 * </ul>
 *
 * <p>关键词来源：由视觉模型直接提取发送场景情绪词（auto_keywords），管理员可覆盖（keywords 字段）。
 * StickerTool 检索时优先用 keywords，回退到 auto_keywords，最后回退到 face 表情。
 *
 * <p>存储位置（相对运行目录）：
 * <ul>
 *     <li>元数据：{@code data/stickers.json}（可通过 STICKER_JSON 环境变量覆盖）</li>
 *     <li>图片：{@code data/stickers/}（可通过 STICKER_DIR 环境变量覆盖）</li>
 * </ul>
 */
public final class StickerIngestService {

    private static final Logger logger = LoggerFactory.getLogger(StickerIngestService.class);

    private static final Set<String> EMOTION_KEYWORDS = Set.of(
            "开心", "笑", "哈哈", "无语", "哭", "难过", "生气", "怒", "安慰", "加油", "得意", "嘲讽",
            "震惊", "惊讶", "吃瓜", "嫌弃", "尴尬", "害羞", "滑稽", "崩溃", "委屈", "比心", "笑哭", "裂开",
            "可爱", "萌", "高冷", "治愈", "调皮", "害怕", "困", "累", "饿", "懵", "问号", "再见", "OK",
            "谢谢", "偷笑", "白眼", "打死你", "赞", "厉害", "牛", "爱", "喜欢");

    private static volatile StickerIngestService instance;

    /** 元数据 JSON 路径。 */
    private final Path jsonPath;
    /** 图片存放目录。 */
    private final Path dirPath;

    /** 内存缓存（线程安全）。 */
    private final List<StickerRecord> records = new CopyOnWriteArrayList<>();
    /** 已见 sticker 哈希集合（去重缓存）。随 records 同步增长，文件持久化保证重启后命中。 */
    private final Set<String> seenHashes = Collections.synchronizedSet(new HashSet<>());

    /** 用于 LLM 情绪转译的轻量线程池。 */
    private final ExecutorService ingestExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sticker-ingest");
        t.setDaemon(true);
        return t;
    });

    /**
     * Vision 描述缓存：imageUrl → (desc, ts)。harvestFromUrl 写入，AIHandler 复用。
     * 避免 sticker-ingest 和 conversation 各自调一次 vision（每次 ~1s）。
     * 60s 过期——同一张图短时间内不会重复调。
     */
    private static final long VISION_CACHE_TTL_MS = 60_000L;
    private final ConcurrentHashMap<String, CachedVision> visionCache = new ConcurrentHashMap<>();

    private BaiLianService baiLianService;

    private StickerIngestService() {
        String jsonEnv = System.getProperty("sticker.json", System.getenv("STICKER_JSON"));
        String dirEnv = System.getProperty("sticker.dir", System.getenv("STICKER_DIR"));
        this.jsonPath = Paths.get(jsonEnv != null && !jsonEnv.isBlank() ? jsonEnv : "data/stickers.json");
        this.dirPath = Paths.get(dirEnv != null && !dirEnv.isBlank() ? dirEnv : "data/stickers/");
        initStorage();
        loadFromDisk();
        logger.info("StickerIngestService 初始化：records={}, jsonPath={}, dirPath={}",
                records.size(), jsonPath, dirPath);
    }

    public static synchronized StickerIngestService init() {
        if (instance == null) {
            instance = new StickerIngestService();
        }
        return instance;
    }

    /** BotBootstrap 注入 LLM 依赖。 */
    public void setBaiLianService(BaiLianService service) {
        this.baiLianService = service;
    }

    public static StickerIngestService getInstance() {
        if (instance == null) {
            throw new IllegalStateException("StickerIngestService 未初始化");
        }
        return instance;
    }

    // ===== 公开 API =====

    /** 所有 sticker 记录（不可变快照）。StickerTool 用。 */
    public List<StickerRecord> getAllStickers() {
        return List.copyOf(records);
    }

    /** 关键词检索（任一关键词命中即返回）。 */
    public List<StickerRecord> searchByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String lower = keyword.toLowerCase(Locale.ROOT);
        List<StickerRecord> hits = new ArrayList<>();
        for (StickerRecord r : records) {
            if (matchesKeyword(r, lower)) hits.add(r);
        }
        return hits;
    }

    public StickerRecord getById(String id) {
        if (id == null) return null;
        for (StickerRecord r : records) {
            if (id.equals(r.id)) return r;
        }
        return null;
    }

    /**
     * 解析关键词字符串：支持逗号（含中文）、空格、顿号分隔，也支持 String[] 形式（来自 Tool calling）。
     * AIHandler 和 StickerAdminTool 都用这个。
     */
    @SuppressWarnings("unchecked")
    public static List<String> parseKeywords(Object kwRaw) {
        List<String> out = new ArrayList<>();
        if (kwRaw == null) return out;
        if (kwRaw instanceof List) {
            for (Object o : (List<Object>) kwRaw) {
                if (o != null) out.add(o.toString().trim());
            }
            return out;
        }
        String s = kwRaw.toString().trim();
        if (s.isEmpty()) return out;
        for (String part : s.split("[,\\s，、]+")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 读 sticker 文件字节，供 StickerTool 编码 base64 用。 */
    public byte[] readStickerBytes(StickerRecord r) {
        if (r == null || r.file == null || r.file.isBlank()) return null;
        try {
            Path filePath = resolveStickerPath(r.file);
            return filePath == null ? null : Files.readAllBytes(filePath);
        } catch (IOException e) {
            logger.warn("读取 sticker 文件失败: {}", r.file, e);
            return null;
        }
    }

    /** 判断本地图片是否存在，不读取整张图片，供审阅列表使用。 */
    public boolean hasStickerFile(StickerRecord r) {
        if (r == null || r.file == null || r.file.isBlank()) return false;
        Path filePath = resolveStickerPath(r.file);
        return filePath != null && Files.isRegularFile(filePath);
    }

    /**
     * 返回面板预览所需的图片 MIME 类型。文件路径来自受控的 sticker 元数据，
     * 这里只按扩展名判断，不把原始 URL 暴露给浏览器。
     */
    public String stickerContentType(StickerRecord r) {
        if (r == null || r.file == null) return "application/octet-stream";
        String lower = r.file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    /**
     * 管理员覆盖关键词。返回结果消息（成功 / 失败原因）。
     */
    public String correctKeywords(String stickerId, List<String> newKeywords, String adminUserId) {
        if (stickerId == null || stickerId.isBlank()) return "缺少 sticker_id";
        if (newKeywords == null || newKeywords.isEmpty()) return "缺少 keywords";
        StickerRecord r = getById(stickerId);
        if (r == null) return "找不到 sticker: " + stickerId;

        synchronized (r) {
            r.keywords = new ArrayList<>(newKeywords);
            r.correctedBy = adminUserId;
            r.correctedAt = System.currentTimeMillis();
        }
        persist();
        return "已纠正 " + stickerId + " 关键词为 [" + String.join(",", newKeywords) + "]";
    }

    /**
     * 删除 sticker。返回结果消息。
     */
    public String remove(String stickerId) {
        if (stickerId == null || stickerId.isBlank()) return "缺少 sticker_id";
        StickerRecord r = getById(stickerId);
        if (r == null) return "找不到 sticker: " + stickerId;
        records.remove(r);
        if (r.file != null && !r.file.isBlank()) {
            Path filePath = resolveStickerPath(r.file);
            try { if (filePath != null) Files.deleteIfExists(filePath); } catch (IOException ignored) {}
        }
        persist();
        return "已删除 sticker: " + stickerId;
    }

    /**
     * 异步入库。供 Listener 调用——立即返回，不阻塞消息处理。
     *
     * @param groupId 群号（私聊时为 null）
     * @param userId 发送者
     * @param imageUrl OneBot 给的图片 URL
     * @param description 视觉模型返回的分类 JSON
     */
    public void ingestAsync(String groupId, String userId, String imageUrl, String description) {
        if (imageUrl == null || imageUrl.isBlank()) return;
        String descFinal = description == null ? "" : description;
        // 缓存 vision 描述，供后续 conversation 流程复用
        if (!descFinal.isBlank()) visionCachePut(imageUrl, descFinal);

        ingestExecutor.submit(() -> {
            try {
                byte[] bytes = ImageUtils.downloadImageBytes(imageUrl);
                if (bytes == null || bytes.length == 0) {
                    logger.debug("图片下载失败/空: {}", imageUrl);
                    return;
                }
                if (bytes.length > 3 * 1024 * 1024L) {
                    logger.debug("图片过大(>3MB)，跳过: {} bytes", bytes.length);
                    return;
                }
                boolean isGif = isGifMagic(bytes, imageUrl);
                ClassificationResult cls = parseClassification(descFinal);
                processSticker(bytes, imageUrl, cls == null ? "" : cls.description, cls, isGif, groupId);
            } catch (Exception e) {
                logger.warn("表情包入库失败: url={}, err={}", imageUrl, e.getMessage());
            }
        });
    }

    /**
     * 独立 harvest 路径：消息接收阶段就触发，自己做下载 + vision + 入库。
     * 不依赖 conversation 是否被唤醒，速率限制/无关键 prompt 也能入库。
     * 视觉模型一次完成判定、情绪关键词和审计描述；静图传原图，GIF 传多帧采样。
     */
    public void harvestFromUrl(String groupId, String userId, String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return;
        ingestExecutor.submit(() -> {
            try {
                byte[] bytes = ImageUtils.downloadImageBytes(imageUrl);
                if (bytes == null || bytes.length == 0) {
                    logger.debug("[harvest] 图片下载失败/空: {}", imageUrl);
                    return;
                }
                if (bytes.length > 3 * 1024 * 1024L) {
                    logger.debug("[harvest] 图片过大(>3MB)，跳过: {} bytes", bytes.length);
                    return;
                }
                boolean isGif = isGifMagic(bytes, imageUrl);
                List<String> imageDataUris = new ArrayList<>();
                if (baiLianService == null) {
                    logger.debug("[harvest] vision 未配置，跳过表情包分类");
                } else if (isGif) {
                    try {
                        imageDataUris = ImageUtils.extractGifFrameDataUris(bytes, 3);
                        logger.debug("[harvest] GIF 抽 {} 帧，单次请求完成分类", imageDataUris.size());
                    } catch (Exception e) {
                        logger.debug("[harvest] GIF 帧抽取失败: {}", e.getMessage());
                    }
                } else {
                    String mime = guessMime(bytes);
                    imageDataUris = List.of("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
                }
                String json = baiLianService == null ? "" : baiLianService.classifyStickerImages(imageDataUris);
                ClassificationResult cls = parseClassification(json);
                String desc = cls == null ? "" : cls.description;
                if (desc != null && !desc.isBlank()) visionCachePut(imageUrl, desc);
                processSticker(bytes, imageUrl, desc, cls, isGif, groupId);
            } catch (Exception e) {
                logger.warn("[harvest] 表情包入库失败: url={}, err={}", imageUrl, e.getMessage());
            }
        });
    }

    /**
     * AIHandler 复用 sticker-ingest 已做过的 vision 描述，避免重复调用。
     * 命中返回 desc；未命中或过期返回 null（调用方应自己调 vision）。
     */
    public String tryGetVisionDescription(String imageUrl) {
        return visionCacheGet(imageUrl);
    }

    /**
     * 写 vision 缓存：AIHandler 调完 vision 后写回，供 sticker-ingest 异步 harvest 复用。
     */
    public void cacheVisionDescription(String url, String desc) {
        visionCachePut(url, desc);
    }

    private void visionCachePut(String url, String desc) {
        if (url == null || desc == null) return;
        visionCache.put(url, new CachedVision(desc, System.currentTimeMillis()));
    }

    private String visionCacheGet(String url) {
        if (url == null) return null;
        CachedVision c = visionCache.get(url);
        if (c == null) return null;
        if (System.currentTimeMillis() - c.ts > VISION_CACHE_TTL_MS) {
            visionCache.remove(url);
            return null;
        }
        return c.desc;
    }

    private record CachedVision(String desc, long ts) {}

    // ===== 内部实现 =====

    /** 入库核心：分类结果已由同一次视觉请求生成，这里只做白名单过滤、去重和落盘。 */
    private void processSticker(byte[] bytes, String imageUrl, String description, ClassificationResult cls,
                                boolean isGif, String groupId) {
        List<String> autoKeywords = cls == null ? List.of() : normalizeStickerKeywords(cls.keywords);
        if (cls == null || !cls.isSticker || autoKeywords.isEmpty()) {
            logger.debug("LLM 判断非表情包: desc={} → 跳过", abbreviate(description, 60));
            return;
        }

        // 算 MD5 做 dedup
        String hash = md5(bytes);
        if (seenHashes.contains(hash)) {
            logger.debug("表情包已存在(hash={})", hash);
            return;
        }

        // 推断扩展名（GIF 强制用 .gif，避免 magic 误判）
        String ext = isGif ? ".gif" : guessExtension(imageUrl, bytes);

        // 存文件
        String fileName = hash + ext;
        Path filePath = dirPath.resolve(fileName);
        try {
            Files.write(filePath, bytes);
        } catch (IOException e) {
            logger.warn("写文件失败: {}", filePath, e);
            return;
        }

        // 落库
        StickerRecord r = new StickerRecord();
        r.id = hash;
        r.file = fileName;
        r.keywords = new ArrayList<>(autoKeywords);
        r.autoKeywords = new ArrayList<>(autoKeywords);
        r.description = description;
        r.correctedBy = null;
        r.correctedAt = 0L;
        r.sourceGroup = groupId;
        r.createdAt = System.currentTimeMillis();
        records.add(r);
        seenHashes.add(hash);
        persist();

        logger.info("✅ 表情包入库: id={} file={} isGif={} auto_keywords={} desc={}",
                hash, fileName, isGif, autoKeywords, abbreviate(description, 60));
    }

    /**
     * GIF 检测：magic bytes（"GIF87a"/"GIF89a"）+ URL 后缀启发式。
     * vision 模型只能看到 GIF 第一帧，所以即便描述"白猫背对镜头坐地板"看着像普通照片，
     * 只要是 GIF 动图就按表情包入库（动图 = 表情包/二创 是稳的先验）。
     */
    private static boolean isGifMagic(byte[] bytes, String url) {
        if (bytes != null && bytes.length >= 3
                && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return true;
        }
        if (url != null && url.toLowerCase(Locale.ROOT).contains(".gif")) {
            return true;
        }
        return false;
    }

    private ClassificationResult parseClassification(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            // 提取 JSON 对象
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end < 0 || end <= start) {
                logger.debug("LLM 分类解析失败：无 JSON 对象: {}", abbreviate(text, 100));
                return null;
            }
            String json = text.substring(start, end + 1);
            ObjectMapper mapper = new ObjectMapper();
            ClassificationResult r = mapper.readValue(json, ClassificationResult.class);
            if (r.keywords == null) r.keywords = new ArrayList<>();
            return r;
        } catch (Exception e) {
            logger.debug("LLM 分类 JSON 解析失败: {} | text={}", e.getMessage(), abbreviate(text, 100));
            return null;
        }
    }

    static List<String> normalizeStickerKeywords(List<String> rawKeywords) {
        List<String> normalized = new ArrayList<>();
        if (rawKeywords == null) return normalized;
        for (String raw : rawKeywords) {
            if (raw == null) continue;
            String keyword = raw.trim();
            if (EMOTION_KEYWORDS.contains(keyword) && !normalized.contains(keyword)) {
                normalized.add(keyword);
            }
            if (normalized.size() >= 5) break;
        }
        return normalized;
    }

    private String guessExtension(String url, byte[] bytes) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains(".png")) return ".png";
        if (lower.contains(".gif")) return ".gif";
        if (lower.contains(".webp")) return ".webp";
        if (lower.contains(".bmp")) return ".bmp";
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return ".jpg";
        // 简单 magic byte 检测
        if (bytes.length >= 4) {
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return ".png";
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return ".gif";
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) return ".jpg";
        }
        return ".jpg";
    }

    /**
     * 根据 magic bytes 推测 MIME（harvestFromUrl 调 vision 时拼 data URI 用）。
     */
    private static String guessMime(byte[] bytes) {
        if (bytes == null || bytes.length < 3) return "image/jpeg";
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return "image/jpeg";
    }

    private static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(data));
        }
    }

    private boolean matchesKeyword(StickerRecord r, String lower) {
        for (String k : r.keywords) {
            if (k != null && lower.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        for (String k : r.autoKeywords) {
            if (k != null && lower.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    // ===== 持久化 =====

    private void initStorage() {
        try {
            Files.createDirectories(dirPath);
        } catch (IOException e) {
            logger.warn("创建 sticker 目录失败: {}", dirPath, e);
        }
        try {
            if (jsonPath.getParent() != null) {
                Files.createDirectories(jsonPath.getParent());
            }
        } catch (IOException ignored) {}
    }

    private void loadFromDisk() {
        if (!Files.exists(jsonPath)) {
            // 第一次启动：尝试从 classpath 迁移旧的 stickers.json（保持向后兼容）
            migrateFromClasspath();
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            byte[] data = Files.readAllBytes(jsonPath);
            List<StickerRecord> loaded = mapper.readValue(data, new TypeReference<List<StickerRecord>>() {});
            records.clear();
            for (StickerRecord r : loaded) {
                records.add(r);
                if (r.id != null) seenHashes.add(r.id);
            }
        } catch (Exception e) {
            logger.warn("读取 stickers.json 失败: {}", e.getMessage());
        }
    }

    private void migrateFromClasspath() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("stickers/stickers.json")) {
            if (is == null) return;
            ObjectMapper mapper = new ObjectMapper();
            List<LegacySticker> legacy = mapper.readValue(is, new TypeReference<List<LegacySticker>>() {});
            long now = System.currentTimeMillis();
            for (LegacySticker ls : legacy) {
                StickerRecord r = new StickerRecord();
                r.id = "legacy_" + Integer.toHexString(records.size()) + "_" + Long.toHexString(now);
                r.file = ls.file != null ? ls.file : "";
                r.keywords = ls.keywords != null ? new ArrayList<>(ls.keywords) : new ArrayList<>();
                r.autoKeywords = new ArrayList<>(r.keywords);
                r.description = "(legacy face-fallback entry)";
                r.correctedBy = null;
                r.correctedAt = 0L;
                r.sourceGroup = null;
                r.createdAt = now;
                records.add(r);
            }
            persist();
            logger.info("已从 classpath 迁移 {} 条 legacy sticker", legacy.size());
        } catch (Exception e) {
            logger.debug("legacy 迁移跳过: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            byte[] data = mapper.writeValueAsBytes(List.copyOf(records));
            Files.write(jsonPath, data);
        } catch (Exception e) {
            logger.warn("写 stickers.json 失败: {}", e.getMessage());
        }
    }

    /** 只允许访问 sticker 目录内的相对文件，避免损坏的元数据造成路径穿越。 */
    private Path resolveStickerPath(String file) {
        if (file == null || file.isBlank()) return null;
        Path root = dirPath.toAbsolutePath().normalize();
        Path resolved = root.resolve(file).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ===== 内部类型 =====

    /** 一条 sticker 记录。公开字段便于 Jackson 序列化。 */
    public static class StickerRecord {
        public String id;
        public String file;
        public List<String> keywords = new ArrayList<>();
        public List<String> autoKeywords = new ArrayList<>();
        public String description;
        public String correctedBy;
        public long correctedAt;
        public String sourceGroup;
        public long createdAt;
    }

    /** classpath 旧格式的临时结构。 */
    public static class LegacySticker {
        public String file;
        public List<String> keywords;
    }

    /** 视觉模型综合判断结果：入库标记、情绪关键词和审计描述。 */
    public static class ClassificationResult {
        public boolean isSticker;
        public List<String> keywords = new ArrayList<>();
        public String description;
    }
}
