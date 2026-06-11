package com.start.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static int baiLianTimeoutMs;
    private static int baiLianMaxRetries;

    private static String agentApiKey;
    private static String agentBaseUrl;
    private static String agentModel;
    private static int agentTimeoutMs;
    private static int agentMaxRetries;

    private static String ttsBaseUrl;
    private static String ttsDefaultVoice;
    private static String ttsAudioFormat;
    private static String ttsOutputDir;
    private static int ttsTimeoutMs;
    private static int ttsMaxRetries;

    private static String merchantApiBaseUrl;
    private static String merchantApiKey;
    private static boolean merchantNotifyEnabled;
    private static Set<Long> merchantNotifyGroups;
    private static Set<Long> merchantNotifyQqs;
    private static Set<String> merchantHighValueItems;

    private static int httpConnectTimeoutMs;
    private static String webSearchUrl;
    private static String webSearchBackend;

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?\\}");

    static {
        try (InputStream is = BotConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new RuntimeException("❌ 未找到 application.properties");
            }

            Properties props = new Properties();
            // 👇 关键：用 UTF-8 显式解码！
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));

            // 读取配置
            String qqStr = props.getProperty("bot.qq");
            if (qqStr == null || qqStr.trim().isEmpty()) {
                throw new RuntimeException("❌ 请配置 bot.qq");
            }
            botQq = Long.parseLong(resolve(qqStr.trim()));
            adminQq = Long.parseLong(resolve(props.getProperty("admin.qq", "0").trim()));
            oneBotHttpBaseUrl = resolve(props.getProperty("onebot.http-base-url", "http://127.0.0.1:5700").trim());
            wsBaseUrl = resolve(props.getProperty("ws.base.url", "ws://127.0.0.1:5700").trim());
            wsUrl = resolve(props.getProperty("ws.url", wsBaseUrl).trim());
            oneBotAccessToken = resolve(props.getProperty("onebot.access-token", "").trim());
            botName = props.getProperty("bot.name", "糖果熊").trim();
            String enabledStr = resolve(props.getProperty("private.whitelist.enabled", "false").trim());
            privateWhitelistEnabled = Boolean.parseBoolean(enabledStr);
            ALLOWED_GROUPS = parseLongSet(resolve(props.getProperty("allowed.groups", "")));
            ALLOWED_PRIVATE_USERS = parseLongSet(resolve(props.getProperty("allowed.private.users", "")));
            PRIVATE_BLACKLIST = parseLongSet(resolve(props.getProperty("private.blacklist", "")));

            baiLianApiKey = resolve(props.getProperty("bailian.api-key", resolve(props.getProperty("dashscope.api-key", ""))).trim());
            baiLianBaseUrl = resolve(props.getProperty("bailian.base-url", "https://api.meai.cloud/v1/chat/completions").trim());
            baiLianChatModel = resolve(props.getProperty("bailian.chat-model", "glm-5.1").trim());
            baiLianTimeoutMs = parseInt(resolve(props.getProperty("bailian.timeout-ms", "90000")), 90000);
            baiLianMaxRetries = parseInt(resolve(props.getProperty("bailian.max-retries", "2")), 2);

            agentApiKey = resolve(props.getProperty("agent.api-key", "").trim());
            agentBaseUrl = resolve(props.getProperty("agent.base-url", "https://api.deepseek.com/v1/chat/completions").trim());
            agentModel = resolve(props.getProperty("agent.model", "gemini-3-flash").trim());
            agentTimeoutMs = parseInt(resolve(props.getProperty("agent.timeout-ms", "90000")), 90000);
            agentMaxRetries = parseInt(resolve(props.getProperty("agent.max-retries", "2")), 2);

            ttsBaseUrl = resolve(props.getProperty("tts.base-url", "http://127.0.0.1:8765").trim());
            ttsDefaultVoice = resolve(props.getProperty("tts.default-voice", "tangguoxiong").trim());
            ttsAudioFormat = resolve(props.getProperty("tts.audio-format", "mp3").trim());
            ttsTimeoutMs = parseInt(resolve(props.getProperty("tts.timeout-ms", "30000")), 30000);
            ttsOutputDir = resolve(props.getProperty("tts.output-dir", "/opt/qq-bot/tts/output").trim());
            ttsMaxRetries = parseInt(resolve(props.getProperty("tts.max-retries", "2")), 2);

            merchantApiBaseUrl = resolve(props.getProperty("merchant.api.base-url", "https://wegame.shallow.ink"));
            merchantApiKey = resolve(props.getProperty("merchant.api.key", ""));
            merchantNotifyEnabled = Boolean.parseBoolean(resolve(props.getProperty("merchant.notify.enabled", "true")));
            merchantNotifyGroups = parseLongSet(resolve(props.getProperty("merchant.notify.groups", "")));
            if (merchantNotifyGroups.isEmpty()) {
                merchantNotifyGroups = ALLOWED_GROUPS;
            }
            merchantNotifyQqs = parseLongSet(resolve(props.getProperty("merchant.notify.qqs", "")));
            merchantHighValueItems = parseStringSet(resolve(props.getProperty("merchant.high-value-items", "国王球,炫彩精灵蛋,首领血脉,棱镜球")));

            httpConnectTimeoutMs = parseInt(resolve(props.getProperty("http.connect-timeout-ms", "10000")), 10000);
            webSearchUrl = resolve(props.getProperty("web.search.url", "https://html.duckduckgo.com/html/"));
            webSearchBackend = resolve(props.getProperty("web.search.backend", "bing"));

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
            logger.info("🔊 TTS 服务: {} (voice={}, format={})", ttsBaseUrl, ttsDefaultVoice, ttsAudioFormat);
        } catch (Exception e) {
            logger.error("❌ 加载配置失败", e);
            throw new RuntimeException("配置加载失败，请检查 application.properties", e);
        }
    }

    private static String resolve(String value) {
        if (value == null) return null;
        Matcher m = ENV_PATTERN.matcher(value.trim());
        if (m.matches()) {
            String envName = m.group(1);
            String envValue = System.getenv(envName);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
            String defaultValue = m.group(2);
            if (defaultValue != null) {
                return defaultValue;
            }
            logger.warn("环境变量 {} 未设置，将使用原始占位符", envName);
        }
        return value;
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Set<Long> parseLongSet(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }

    private static long parseLongSafe(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
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

    public static int getBaiLianTimeoutMs() {
        return baiLianTimeoutMs;
    }

    public static int getBaiLianMaxRetries() {
        return baiLianMaxRetries;
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

    public static String getAt(long userId) {
        return "[CQ:at,qq=" + userId + "]";
    }

}