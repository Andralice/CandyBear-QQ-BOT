package com.start.config;

import com.start.util.EnvResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class BotConfig {
    private static final Logger logger = LoggerFactory.getLogger(BotConfig.class);

    private static long botQq;
    private static long adminQq;
    private static String botName;
    private static boolean privateWhitelistEnabled = false;
    private static Set<Long> ALLOWED_GROUPS = Collections.emptySet();
    private static Set<Long> ALLOWED_PRIVATE_USERS = Collections.emptySet();
    private static Set<Long> PRIVATE_BLACKLIST = Collections.emptySet();
    private static String oneBotHttpBaseUrl;
    private static String oneBotAccessToken;
    private static String wsBaseUrl;
    private static String wsUrl;

    private static String baiLianApiKey;
    private static String baiLianBaseUrl;
    private static String baiLianChatModel;
    private static boolean baiLianMultimodalEnabled;
    private static int baiLianTimeoutMs;
    private static int baiLianMaxRetries;
    private static String baiLianReasoningEffort;

    private static String agentApiKey;
    private static String agentBaseUrl;
    private static String agentModel;
    private static int agentTimeoutMs;
    private static int agentMaxRetries;
    private static String agentReasoningEffort;

    private static String groupCognitionMode;
    private static Set<Long> groupCognitionGroups = Collections.emptySet();
    private static String groupCognitionApiKey;
    private static String groupCognitionBaseUrl;
    private static String groupCognitionModel;
    private static int groupCognitionTimeoutMs;
    private static String groupCognitionReasoningEffort;
    private static String groupCognitionProtocol;
    private static String groupCognitionThinkingType;

    private static String visionApiKey;
    private static String visionBaseUrl;
    private static String visionModel;
    private static int visionTimeoutMs;
    private static boolean visionEnabled;

    private static String ttsBaseUrl;
    private static String ttsDefaultVoice;
    private static String ttsAudioFormat;
    private static String ttsOutputDir;
    private static int ttsTimeoutMs;
    private static int ttsMaxRetries;

    /** 火山引擎 - 豆包语音合成 2.0 TTS 配置。enabled=true 时优先使用，失败 fallback 到 text-to-speech.cn。 */
    private static String volcTtsAccessToken;
    private static String volcTtsVoiceType;
    private static String volcTtsResourceId;
    private static boolean volcTtsEnabled;
    private static int volcTtsTimeoutMs;

    private static String merchantApiBaseUrl;
    private static String merchantApiKey;
    private static boolean merchantNotifyEnabled;
    private static Set<Long> merchantNotifyGroups;
    private static Set<Long> merchantNotifyQqs;
    private static Set<String> merchantHighValueItems;

    private static String auditApiKey;
    private static String auditBaseUrl;
    private static String auditModel;
    private static int auditTimeoutMs;
    private static int httpConnectTimeoutMs;
    private static String webSearchUrl;
    private static String webSearchBackend;

    /** 工作记忆默认过期时间（小时）。LLM 没传 expires_at 时用这个。 */
    private static int workingMemoryDefaultExpireHours = 24;

    static {
        try (InputStream is = BotConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new RuntimeException("❌ 未找到 application.properties");
            }

            Properties props = new Properties();
            // 👇 关键：用 UTF-8 显式解码！
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));

            // 读取配置
            // 5.1 启动链路：BotConfig 静态初始化阶段遇到未替换的 ${VAR} 一律 fallback，
            // 避免在 Main.<clinit> 之前就抛 NumberFormatException 把 preflightCheck 跳过。
            // 真正"必填项缺失"的判断统一推迟到 BotBootstrap.preflightCheck()，由它列出所有缺失项后失败退出。
            String qqStr = resolveOrEmpty(props.getProperty("bot.qq", ""));
            if (qqStr.isEmpty()) {
                logger.warn("⚠️ bot.qq 未配置（BOT_QQ 环境变量缺失或未设置），preflightCheck 阶段会拒绝启动");
                botQq = 0;
            } else {
                botQq = Long.parseLong(qqStr);
            }
            adminQq = parseLongSafe(resolveOrEmpty(props.getProperty("admin.qq", "0")), 0L);
            oneBotHttpBaseUrl = resolveOrEmpty(props.getProperty("onebot.http-base-url", "http://127.0.0.1:5700"));
            wsBaseUrl = resolveOrEmpty(props.getProperty("ws.base.url", "ws://127.0.0.1:5700"));
            wsUrl = resolveOrEmpty(props.getProperty("ws.url", wsBaseUrl));
            oneBotAccessToken = resolveOrEmpty(props.getProperty("onebot.access-token", ""));
            botName = props.getProperty("bot.name", "糖果熊").trim();
            privateWhitelistEnabled = Boolean.parseBoolean(resolveOrEmpty(props.getProperty("private.whitelist.enabled", "false")));
            ALLOWED_GROUPS = parseLongSet(resolveOrEmpty(props.getProperty("allowed.groups", "")));
            ALLOWED_PRIVATE_USERS = parseLongSet(resolveOrEmpty(props.getProperty("allowed.private.users", "")));
            PRIVATE_BLACKLIST = parseLongSet(resolveOrEmpty(props.getProperty("private.blacklist", "")));

            baiLianApiKey = resolveOrEmpty(props.getProperty("bailian.api-key", props.getProperty("dashscope.api-key", "")));
            baiLianBaseUrl = resolveOrEmpty(props.getProperty("bailian.base-url", "https://api.deepseek.com/v1/chat/completions"));
            baiLianChatModel = resolveOrEmpty(props.getProperty("bailian.chat-model", "deepseek-v4-pro"));
            baiLianMultimodalEnabled = Boolean.parseBoolean(
                    resolveOrEmpty(props.getProperty("bailian.multimodal.enabled",
                            "${BAILIAN_MULTIMODAL_ENABLED:false}")));
            baiLianTimeoutMs = parseInt(resolveOrEmpty(props.getProperty("bailian.timeout-ms", "90000")), 90000);
            baiLianMaxRetries = parseInt(resolveOrEmpty(props.getProperty("bailian.max-retries", "2")), 2);
            baiLianReasoningEffort = resolveOrEmpty(props.getProperty("bailian.reasoning-effort", ""));

            agentApiKey = resolveOrEmpty(props.getProperty("agent.api-key", ""));
            agentBaseUrl = resolveOrEmpty(props.getProperty("agent.base-url", "https://api.deepseek.com/v1/chat/completions"));
            agentModel = resolveOrEmpty(props.getProperty("agent.model", "deepseek-v4-pro"));
            agentTimeoutMs = parseInt(resolveOrEmpty(props.getProperty("agent.timeout-ms", "90000")), 90000);
            agentMaxRetries = parseInt(resolveOrEmpty(props.getProperty("agent.max-retries", "2")), 2);
            agentReasoningEffort = resolveOrEmpty(props.getProperty("agent.reasoning-effort", ""));

            groupCognitionMode = resolveOrEmpty(props.getProperty("group-cognition.mode", "off"));
            groupCognitionGroups = parseLongSet(resolveOrEmpty(props.getProperty("group-cognition.groups", "")));
            groupCognitionApiKey = resolveOrEmpty(props.getProperty("group-cognition.api-key", ""));
            if (groupCognitionApiKey.isBlank()) groupCognitionApiKey = baiLianApiKey;
            groupCognitionBaseUrl = resolveOrEmpty(props.getProperty("group-cognition.base-url", ""));
            if (groupCognitionBaseUrl.isBlank()) groupCognitionBaseUrl = baiLianBaseUrl;
            groupCognitionModel = resolveOrEmpty(props.getProperty("group-cognition.model", ""));
            if (groupCognitionModel.isBlank()) groupCognitionModel = baiLianChatModel;
            groupCognitionTimeoutMs = parseInt(
                    resolveOrEmpty(props.getProperty("group-cognition.timeout-ms", "20000")), 20000);
            groupCognitionReasoningEffort = resolveOrEmpty(
                    props.getProperty("group-cognition.reasoning-effort", ""));
            groupCognitionProtocol = resolveOrEmpty(
                    props.getProperty("group-cognition.protocol", "response_format"));
            groupCognitionThinkingType = resolveOrEmpty(
                    props.getProperty("group-cognition.thinking-type", ""));

            visionEnabled = Boolean.parseBoolean(resolveOrEmpty(props.getProperty("vision.enabled", "true")));
            visionApiKey = resolveOrEmpty(props.getProperty("vision.api-key", baiLianApiKey));
            visionBaseUrl = resolveOrEmpty(props.getProperty("vision.base-url", baiLianBaseUrl));
            visionModel = resolveOrEmpty(props.getProperty("vision.model", "qwen-vl-max"));
            visionTimeoutMs = parseInt(resolveOrEmpty(props.getProperty("vision.timeout-ms", "60000")), 60000);

            ttsBaseUrl = resolveOrEmpty(props.getProperty("tts.base-url", "http://127.0.0.1:8765"));
            ttsDefaultVoice = resolveOrEmpty(props.getProperty("tts.default-voice", "tangguoxiong"));
            ttsAudioFormat = resolveOrEmpty(props.getProperty("tts.audio-format", "mp3"));
            ttsTimeoutMs = parseInt(resolveOrEmpty(props.getProperty("tts.timeout-ms", "30000")), 30000);
            ttsOutputDir = resolveOrEmpty(props.getProperty("tts.output-dir", "/opt/qq-bot/tts/output"));
            ttsMaxRetries = parseInt(resolveOrEmpty(props.getProperty("tts.max-retries", "2")), 2);

            volcTtsEnabled = Boolean.parseBoolean(resolveOrEmpty(props.getProperty("volc.tts.enabled", "false")));
            volcTtsAccessToken = resolveOrEmpty(props.getProperty("volc.tts.access-token", ""));
            volcTtsVoiceType = resolveOrEmpty(props.getProperty("volc.tts.voice-type", ""));
            volcTtsResourceId = resolveOrEmpty(props.getProperty("volc.tts.resource-id", "seed-icl-2.0"));
            volcTtsTimeoutMs = parseInt(resolveOrEmpty(props.getProperty("volc.tts.timeout-ms", "15000")), 15000);

            merchantApiBaseUrl = resolveOrEmpty(props.getProperty("merchant.api.base-url", "https://wegame.shallow.ink"));
            merchantApiKey = resolveOrEmpty(props.getProperty("merchant.api.key", ""));
            merchantNotifyEnabled = Boolean.parseBoolean(resolveOrEmpty(props.getProperty("merchant.notify.enabled", "true")));
            merchantNotifyGroups = parseLongSet(resolveOrEmpty(props.getProperty("merchant.notify.groups", "")));
            if (merchantNotifyGroups.isEmpty()) {
                merchantNotifyGroups = ALLOWED_GROUPS;
            }
            merchantNotifyQqs = parseLongSet(resolveOrEmpty(props.getProperty("merchant.notify.qqs", "")));
            merchantHighValueItems = parseStringSet(resolveOrEmpty(props.getProperty("merchant.high-value-items", "国王球,炫彩精灵蛋,首领血脉,棱镜球")));

            httpConnectTimeoutMs = parseInt(resolveOrEmpty(props.getProperty("http.connect-timeout-ms", "10000")), 10000);
            webSearchUrl = resolveOrEmpty(props.getProperty("web.search.url", "https://html.duckduckgo.com/html/"));
            webSearchBackend = resolveOrEmpty(props.getProperty("web.search.backend", "bing"));

            auditApiKey = resolveOrEmpty(props.getProperty("audit.api-key", ""));
            auditBaseUrl = resolveOrEmpty(props.getProperty("audit.base-url", "https://api.mytokenland.com/v1/chat/completions"));
            auditModel = resolveOrEmpty(props.getProperty("audit.model", "claude-sonnet-4-6"));
            // 审计模型允许较长推理时间，默认等待 120 秒；可由 AUDIT_TIMEOUT_MS 覆盖。
            auditTimeoutMs = parseInt(resolveOrEmpty(props.getProperty("audit.timeout-ms", "120000")), 120000);

            workingMemoryDefaultExpireHours = parseInt(
                    resolveOrEmpty(props.getProperty("working_memory.default_expire_hours", "24")), 24);

            logger.info("🤖 机器人 QQ: {}, 名字: {}", botQq, botName);
            logger.info("✅ WebSocket 地址: {}", wsUrl);
            logger.info("✅ OneBot HTTP 地址: {}", oneBotHttpBaseUrl);
            logger.info("✅ 白名单群: {}", ALLOWED_GROUPS);
            logger.info("🔒 私聊白名单开关: {}", privateWhitelistEnabled ? "ON" : "OFF");
            if (privateWhitelistEnabled) {
                logger.info("✅ 私聊白名单用户: {}", ALLOWED_PRIVATE_USERS);
            } else {
                logger.info("✅ 所有私聊消息将被允许");
            }
            logger.info("🧭 群聊并行认知: mode={}, groups={}, model={}",
                    groupCognitionMode, groupCognitionGroups, groupCognitionModel);
            logger.info("🖼️ 聊天图片模式: 独立 Vision 描述（主聊天不发送原图）");
            logger.info("🔊 TTS 服务: {} (voice={}, format={})", ttsBaseUrl, ttsDefaultVoice, ttsAudioFormat);
            if (volcTtsEnabled) {
                logger.info("🔥 豆包语音合成 2.0 TTS: enabled, voiceType={}, resourceId={}, timeout={}ms",
                        volcTtsVoiceType, volcTtsResourceId, volcTtsTimeoutMs);
            } else {
                logger.info("🔥 豆包语音合成 2.0 TTS: disabled（fallback 到 text-to-speech.cn）");
            }
        } catch (Exception e) {
            logger.error("❌ 加载配置失败", e);
            throw new RuntimeException("配置加载失败，请检查 application.properties", e);
        }
    }

    /**
     * 判定字符串是否为未替换的 ${ENV_VAR} 占位符。
     * 当环境变量缺失且 application.properties 中没有默认值时，EnvResolver 会原样返回 ${...}。
     * BotConfig 静态初始化阶段把这种情况当作"未配置"，不让它一路传到 Long.parseLong / parseInt 炸出 NumberFormatException，
     * 把真正的失败推迟到 preflightCheck 统一报告。
     *
     * <p>包内可见，方便测试和 BotBootstrap 复用判定逻辑。
     */
    static boolean isUnresolvedPlaceholder(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        return trimmed.startsWith("${") && trimmed.endsWith("}")
                && trimmed.length() >= 3;
    }

    /**
     * 解析 env 占位符；如果仍是 ${...} 未替换，返回空字符串而不是占位符原串。
     * 用于 BotConfig 静态初始化阶段所有 string 字段，保证 Main.&lt;clinit&gt; 不会因
     * NumberFormatException 把后续 preflightCheck 跳过。
     */
    private static String resolveOrEmpty(String value) {
        if (value == null) return "";
        String resolved = EnvResolver.resolve(value);
        if (resolved == null || isUnresolvedPlaceholder(resolved)) {
            return "";
        }
        return resolved.trim();
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || isUnresolvedPlaceholder(trimmed)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析为 long 集合。占位符元素（${VAR}）会被过滤，避免 BotConfig 静态初始化阶段抛 NumberFormatException。
     * 整个 value 是 ${...} 时也返回空集，preflightCheck 会把它判为"未配置"。
     */
    static Set<Long> parseLongSet(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        String trimmed = value.trim();
        if (isUnresolvedPlaceholder(trimmed)) {
            return Collections.emptySet();
        }
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !isUnresolvedPlaceholder(s))
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }

    /**
     * 解析为 long。空 / 占位符 / 解析失败时返回 defaultValue，BotConfig 静态初始化阶段不抛异常。
     * 失败的字段在 preflightCheck 统一报错。
     */
    static long parseLongSafe(String value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || isUnresolvedPlaceholder(trimmed)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            logger.warn("⚠️ 配置值无法解析为 long: '{}', 使用默认值 {}", trimmed, defaultValue);
            return defaultValue;
        }
    }

    private static Set<String> parseStringSet(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public static long getBotQq() {
        return botQq;
    }

    public static long getAdminQq() {
        return adminQq;
    }

    public static String getBotName() {
        return botName;
    }

    public static boolean isPrivateWhitelistEnabled() {
        return privateWhitelistEnabled;
    }

    public static Set<Long> getAllowedGroups() {
        return ALLOWED_GROUPS;
    }

    public static Set<Long> getAllowedPrivateUsers() {
        return ALLOWED_PRIVATE_USERS;
    }
    public static Set<Long> getPrivateBlacklist() {
        return PRIVATE_BLACKLIST;
    }

    public static String getOneBotHttpBaseUrl() {
        return oneBotHttpBaseUrl;
    }

    public static String getOneBotAccessToken() {
        return oneBotAccessToken;
    }

    public static String getWsBaseUrl() {
        return wsBaseUrl;
    }

    public static String getWsUrl() {
        return wsUrl;
    }

    public static String getBaiLianApiKey() {
        return baiLianApiKey;
    }

    public static String getBaiLianBaseUrl() {
        return baiLianBaseUrl;
    }

    public static String getBaiLianChatModel() {
        return baiLianChatModel;
    }

    public static boolean isBaiLianMultimodalEnabled() {
        return baiLianMultimodalEnabled;
    }

    public static int getBaiLianTimeoutMs() {
        return baiLianTimeoutMs;
    }

    public static int getBaiLianMaxRetries() {
        return baiLianMaxRetries;
    }

    public static String getBaiLianReasoningEffort() {
        return baiLianReasoningEffort;
    }

    public static String getAgentApiKey() {
        return agentApiKey;
    }

    public static String getAgentBaseUrl() {
        return agentBaseUrl;
    }

    public static String getAgentModel() {
        return agentModel;
    }

    public static int getAgentTimeoutMs() {
        return agentTimeoutMs;
    }

    public static int getAgentMaxRetries() {
        return agentMaxRetries;
    }

    public static String getAgentReasoningEffort() {
        return agentReasoningEffort;
    }

    public static String getGroupCognitionMode() { return groupCognitionMode; }
    public static Set<Long> getGroupCognitionGroups() { return groupCognitionGroups; }
    public static String getGroupCognitionApiKey() { return groupCognitionApiKey; }
    public static String getGroupCognitionBaseUrl() { return groupCognitionBaseUrl; }
    public static String getGroupCognitionModel() { return groupCognitionModel; }
    public static int getGroupCognitionTimeoutMs() { return groupCognitionTimeoutMs; }
    public static String getGroupCognitionReasoningEffort() { return groupCognitionReasoningEffort; }
    public static String getGroupCognitionProtocol() { return groupCognitionProtocol; }
    public static String getGroupCognitionThinkingType() { return groupCognitionThinkingType; }

    public static int getWorkingMemoryDefaultExpireHours() {
        return workingMemoryDefaultExpireHours;
    }

    public static boolean isVisionEnabled() { return visionEnabled; }
    public static String getVisionApiKey() { return visionApiKey; }
    public static String getVisionBaseUrl() { return visionBaseUrl; }
    public static String getVisionModel() { return visionModel; }
    public static int getVisionTimeoutMs() { return visionTimeoutMs; }

    public static String getTtsBaseUrl() {
        return ttsBaseUrl;
    }

    public static String getTtsDefaultVoice() {
        return ttsDefaultVoice;
    }

    public static String getTtsAudioFormat() {
        return ttsAudioFormat;
    }

    public static String getTtsOutputDir() {
        return ttsOutputDir;
    }

    public static int getTtsTimeoutMs() {
        return ttsTimeoutMs;
    }

    public static int getTtsMaxRetries() {
        return ttsMaxRetries;
    }

    public static boolean isVolcTtsEnabled() { return volcTtsEnabled; }
    public static String getVolcTtsAccessToken() { return volcTtsAccessToken; }
    public static String getVolcTtsVoiceType() { return volcTtsVoiceType; }
    public static String getVolcTtsResourceId() { return volcTtsResourceId; }
    public static int getVolcTtsTimeoutMs() { return volcTtsTimeoutMs; }

    public static int getHttpConnectTimeoutMs() {
        return httpConnectTimeoutMs;
    }

    public static String getWebSearchUrl() {
        return webSearchUrl;
    }

    public static String getWebSearchBackend() {
        return webSearchBackend;
    }

    public static String getMerchantApiBaseUrl() { return merchantApiBaseUrl; }

    public static String getMerchantApiKey() { return merchantApiKey; }

    public static boolean isMerchantNotifyEnabled() { return merchantNotifyEnabled; }

    public static Set<Long> getMerchantNotifyGroups() { return merchantNotifyGroups; }

    public static Set<Long> getMerchantNotifyQqs() { return merchantNotifyQqs; }

    public static Set<String> getMerchantHighValueItems() { return merchantHighValueItems; }

    public static String getAuditApiKey() { return auditApiKey; }
    public static String getAuditBaseUrl() { return auditBaseUrl; }
    public static String getAuditModel() { return auditModel; }
    public static int getAuditTimeoutMs() { return auditTimeoutMs; }

    public static String getAt(long userId) {
        return "[CQ:at,qq=" + userId + "]";
    }

}
