package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 笑话服务类
 * <p>
 * 提供从外部 API 获取随机笑话的功能。
 * 支持单段式笑话（single）和双段式笑话（twopart，包含铺垫和 punchline）。
 * 使用 Java 11+ 的 HttpClient 进行网络请求，并使用 Jackson 处理 JSON 响应。
 * </p>
 */
public class JokeService {
    private static final Logger logger = LoggerFactory.getLogger(JokeService.class);
    private static final String JOKE_API_URL = "https://v2.jokeapi.dev/joke/Any?safe-mode";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * 从免费 API 获取一条随机笑话
     * @return 笑话文本；失败时返回友好提示
     */
    public static String fetchRandomJoke() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(JOKE_API_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "NapCat-Joke-Bot/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = JSON_MAPPER.readTree(response.body());

                // 检查是否是 twopart 类型
                if ("twopart".equals(json.path("type").asText())) {
                    String setup = json.path("setup").asText().trim();
                    String delivery = json.path("delivery").asText().trim();
                    return setup + "\n" + delivery; // 或用 " —— "、"（答案：...）" 等连接
                }
                // 否则是 single 类型
                else if ("single".equals(json.path("type").asText())) {
                    return json.path("joke").asText().trim();
                }
            }

            logger.warn("笑话 API 返回格式异常: {}", response.body());
            return "今天段子手请假了，明天再来笑吧~";

        } catch (Exception e) {
            logger.error("获取笑话时发生异常", e);
            return "哎呀，网络不给力，笑话飞走了！(。-`ω´-)";
        }
    }
}