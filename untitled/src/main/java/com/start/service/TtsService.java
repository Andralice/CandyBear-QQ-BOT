package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TTS 语音合成服务，调用 text-to-speech.cn 免费在线 API。
 * 无需 GPU、无需 Python 服务、无需 API Key。
 */
public class TtsService {
    private static final Logger logger = LoggerFactory.getLogger(TtsService.class);

    private static final String API_PAGE = "https://www.text-to-speech.cn/";
    private static final String API_ENDPOINT = "https://www.text-to-speech.cn/getSpeek.php";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("const token = '([^']+)'");

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(BotConfig.getHttpConnectTimeoutMs()))
            .cookieHandler(new CookieManager())
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String defaultVoice;
    private final int requestTimeoutMs;
    private final int maxRetries;
    private final Path outputDir;

    /** 缓存的 token，避免每次请求都抓首页 */
    private volatile String cachedToken;
    private volatile long tokenExpireAt;

    /**
     * 火山引擎 TTS 引擎（可选）。
     * 如果设置且配置完整，synthesize() 优先调用它，失败自动 fallback 到 text-to-speech.cn。
     */
    private VolcTtsEngine volcTtsEngine;

    public TtsService() {
        this(true);
    }

    public TtsService(boolean prepareOutputDirectory) {
        this.defaultVoice = BotConfig.getTtsDefaultVoice();
        this.requestTimeoutMs = BotConfig.getTtsTimeoutMs();
        this.maxRetries = BotConfig.getTtsMaxRetries();
        this.outputDir = Paths.get(BotConfig.getTtsOutputDir());
        if (prepareOutputDirectory) {
            try {
                Files.createDirectories(outputDir);
            } catch (IOException e) {
                logger.warn("Cannot create TTS output dir: {}", outputDir);
            }
        }
        logger.info("TTS Service: text-to-speech.cn, voice={}, output={}", defaultVoice, outputDir);
    }

    // ==================== 公开 API ====================

    public byte[] synthesize(String text) {
        return synthesize(text, defaultVoice, null);
    }

    public byte[] synthesize(String text, String voice) {
        return synthesize(text, voice, null);
    }

    /**
     * 合成语音（火山引擎豆包语音 2.0 支持 instruct / 语音指令）。
     *
     * @param text     待合成文本
     * @param voice    备用音色（仅在 fallback 到 text-to-speech.cn 时使用）
     * @param instruct 语音指令（撒娇/吐槽/安慰 等），仅传给火山引擎。
     *                 复刻音色（S_ZSLtoFZb2）官方文档说可能不支持，失败时自动 fallback。
     */
    public byte[] synthesize(String text, String voice, String instruct) {
        if (text == null || text.isBlank()) {
            logger.warn("TTS text is empty");
            return null;
        }

        // 火山引擎优先（火山引擎没有 voice 概念，复刻音色是固定的）
        if (volcTtsEngine != null && volcTtsEngine.isReady()) {
            byte[] volcAudio = volcTtsEngine.synthesize(text, instruct);
            if (volcAudio != null && volcAudio.length > 0) {
                return volcAudio;
            }
            logger.warn("VolcTTS 失败，fallback 到 text-to-speech.cn: text='{}', instruct='{}'", text, instruct);
        }

        if (text.length() > 300) text = text.substring(0, 300);

        int retry = 0;
        while (retry <= maxRetries) {
            try {
                String token = getToken();
                if (token == null) {
                    logger.warn("Failed to get TTS token");
                    return null;
                }

                // Step 1: POST text → get download URL
                String downloadUrl = requestTts(text, voice, token);
                if (downloadUrl == null) {
                    // token 可能过期，重新获取
                    cachedToken = null;
                    retry++;
                    continue;
                }

                // Step 2: Download MP3
                byte[] audio = downloadMp3(downloadUrl);
                if (audio != null && audio.length > 0) {
                    logger.debug("TTS success: {} bytes", audio.length);
                    return audio;
                }
                logger.warn("TTS download returned empty");
                retry++;
            } catch (Exception e) {
                logger.warn("TTS error (retry {}/{}): {}", retry, maxRetries, e.getMessage());
                retry++;
                if (retry <= maxRetries) {
                    try { Thread.sleep(1000L * retry); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                }
            }
        }
        logger.error("TTS failed after {} retries", maxRetries);
        return null;
    }

    public String synthesizeToBase64(String text) {
        byte[] audio = synthesize(text);
        if (audio == null) return null;
        return Base64.getEncoder().encodeToString(audio);
    }

    /**
     * 合成语音并保存到本地文件，返回绝对路径。失败返回 null。
     */
    public String synthesizeToFile(String text) {
        byte[] audio = synthesize(text);
        if (audio == null) return null;
        try {
            String filename = UUID.randomUUID() + ".mp3";
            Path filePath = outputDir.resolve(filename);
            Files.write(filePath, audio);
            logger.debug("TTS saved: {}", filePath);
            return filePath.toAbsolutePath().toString();
        } catch (IOException e) {
            logger.error("Failed to write TTS file: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 内部实现 ====================

    private String getToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return cachedToken;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_PAGE))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            Matcher m = TOKEN_PATTERN.matcher(resp.body());
            if (m.find()) {
                cachedToken = m.group(1);
                tokenExpireAt = System.currentTimeMillis() + 600_000; // 缓存 10 分钟
                logger.debug("TTS token refreshed: {}...", cachedToken.substring(0, 20));
                return cachedToken;
            }
            logger.warn("Token not found in page");
            return null;
        } catch (Exception e) {
            logger.warn("Failed to fetch TTS token: {}", e.getMessage());
            return null;
        }
    }

    private String requestTts(String text, String voice, String token) {
        try {
            String body = "language=zh-CN"
                    + "&voice=" + urlEncode(voice)
                    + "&text=" + urlEncode(text)
                    + "&role=0&style=0&styledegree=1&volume=50"
                    + "&rate=0&pitch=0"
                    + "&kbitrate=audio-16khz-32kbitrate-mono-mp3"
                    + "&silence=0"
                    + "&user_id=&yzm="
                    + "&token=" + urlEncode(token);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_ENDPOINT))
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Origin", "https://www.text-to-speech.cn")
                    .header("Referer", "https://www.text-to-speech.cn/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.warn("TTS API HTTP {}", resp.statusCode());
                return null;
            }

            JsonNode json = objectMapper.readTree(resp.body());
            int code = json.path("code").asInt();
            if (code == 200) {
                String downloadUrl = json.path("download").asText();
                logger.debug("TTS download URL: {}", downloadUrl);
                return downloadUrl;
            }

            logger.warn("TTS API error code={}, msg={}", code, json.path("msg").asText());
            return null;
        } catch (Exception e) {
            logger.warn("TTS request failed: {}", e.getMessage());
            return null;
        }
    }

    private byte[] downloadMp3(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
            logger.warn("MP3 download HTTP {}", resp.statusCode());
            return null;
        } catch (Exception e) {
            logger.warn("MP3 download failed: {}", e.getMessage());
            return null;
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * 注入火山引擎 TTS。设置后 synthesize 优先调用火山引擎，失败 fallback 到 text-to-speech.cn。
     * 如果不调用此方法，则保持纯 text-to-speech.cn 行为（向后兼容）。
     */
    public void setVolcTtsEngine(VolcTtsEngine volcTtsEngine) {
        this.volcTtsEngine = volcTtsEngine;
        if (volcTtsEngine != null && volcTtsEngine.isReady()) {
            logger.info("✅ TtsService 启用火山引擎声音复刻（fallback: text-to-speech.cn）");
        }
    }
}
