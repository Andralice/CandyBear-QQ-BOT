package com.start.agent;

import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页搜索工具。支持百度、Bing、DuckDuckGo，通过 web.search.backend 配置切换。
 */
public class WebSearchTool implements Tool {
    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String BACKEND = BotConfig.getWebSearchBackend();

    @Override public String getName() { return "web_search"; }

    @Override public String getDescription() {
        return "搜索网页。当用户问你一个你不太确定的事（新闻、知识、事实），先调这个查一下，再基于结果回答。不要瞎编。";
    }

    @Override public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "搜索关键词")
                ),
                "required", List.of("query"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) return "缺少搜索词";

        try {
            String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            String url = buildUrl(encoded);
            logger.debug("WebSearch [{}]: {}", BACKEND, url);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "搜索失败 HTTP " + resp.statusCode();
            }

            List<String> results = parseResults(resp.body());
            if (results.isEmpty()) {
                return "未找到相关结果，换个说法试试？";
            }

            StringBuilder sb = new StringBuilder("搜索结果：\n");
            for (int i = 0; i < Math.min(results.size(), 5); i++) {
                sb.append(i + 1).append(". ").append(results.get(i)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Web search [{}] failed: {}", BACKEND, e.getMessage());
            return "搜索出错: " + e.getMessage();
        }
    }

    // ===== URL 构建 =====

    private String buildUrl(String encodedQuery) {
        return switch (BACKEND) {
            case "baidu" -> "https://www.baidu.com/s?wd=" + encodedQuery;
            case "bing"  -> "https://www.bing.com/search?q=" + encodedQuery;
            default      -> "https://html.duckduckgo.com/html/?q=" + encodedQuery;
        };
    }

    // ===== HTML 解析 =====

    private List<String> parseResults(String html) {
        return switch (BACKEND) {
            case "baidu" -> parseBaidu(html);
            case "bing"  -> parseBing(html);
            default      -> parseDuckDuckGo(html);
        };
    }

    /** 百度：<div class="result c-container" ...> 块，标题在 <h3><a>，摘要 <span class="content-right_..."> */
    private List<String> parseBaidu(String html) {
        List<String> results = new ArrayList<>();
        // 找所有 result 块
        Matcher blockMatcher = Pattern.compile(
                "<div[^>]*class=\"[^\"]*result[^\"]*c-container[^\"]*\"[^>]*>(.*?)</div>\\s*</div>",
                Pattern.DOTALL).matcher(html);
        while (blockMatcher.find() && results.size() < 5) {
            String block = blockMatcher.group(1);
            // 标题
            String title = extractBetween(block, "<a", "</a>");
            title = title != null ? title.replaceAll("<[^>]+>", "").trim() : "";
            // 摘要
            String snippet = "";
            Matcher sm = Pattern.compile("<span[^>]*class=\"[^\"]*content-right_[^\"]*\"[^>]*>(.*?)</span>",
                    Pattern.DOTALL).matcher(block);
            if (sm.find()) snippet = sm.group(1).replaceAll("<[^>]+>", "").trim();
            // URL
            String url = extractBetween(block, "href=\"", "\"");

            if (!title.isEmpty() && !title.equals("广告")) {
                results.add(title + (snippet.isEmpty() ? "" : " - " + snippet));
            }
        }
        return results;
    }

    /** Bing: <li class="b_algo"> 块，标题在 <h2><a>，摘要 <p> 或 <div class="b_caption"> */
    private List<String> parseBing(String html) {
        List<String> results = new ArrayList<>();
        String[] blocks = html.split("<li class=\"b_algo\"");
        for (int i = 1; i < blocks.length && results.size() < 5; i++) {
            String block = blocks[i];
            String title = extractBetween(block, "<a", "</a>");
            title = title != null ? title.replaceAll("<[^>]+>", "").trim() : "";
            String snippet = "";
            Matcher sm = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(block);
            if (sm.find()) snippet = sm.group(1).replaceAll("<[^>]+>", "").trim();
            if (snippet.length() > 200) snippet = snippet.substring(0, 200);

            if (!title.isEmpty()) {
                results.add(title + (snippet.isEmpty() ? "" : " - " + snippet));
            }
        }
        return results;
    }

    /** DuckDuckGo HTML: <div class="result__body"> 块 */
    private List<String> parseDuckDuckGo(String html) {
        List<String> results = new ArrayList<>();
        String[] blocks = html.split("class=\"result__body\"");
        for (int i = 1; i < blocks.length && results.size() < 5; i++) {
            String block = blocks[i];
            String title = extractBetween(block, "class=\"result__a\"", "</a>");
            title = title != null ? title.replaceAll("<[^>]+>", "").trim() : "";
            String snippet = extractBetween(block, "class=\"result__snippet\"", "</a>");
            snippet = snippet != null ? snippet.replaceAll("<[^>]+>", "").trim() : "";

            if (!title.isEmpty()) {
                results.add(title + (snippet.isEmpty() ? "" : " - " + snippet));
            }
        }
        return results;
    }

    // ===== 辅助方法 =====

    private String extractBetween(String text, String start, String end) {
        int si = text.indexOf(start);
        if (si < 0) return null;
        si += start.length();
        if (end.equals("</a>")) {
            int gt = text.indexOf(">", si);
            if (gt >= 0) si = gt + 1;
        }
        int ei = text.indexOf(end, si);
        return ei >= 0 ? text.substring(si, ei).trim() : null;
    }
}
