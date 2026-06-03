package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 网页搜索工具。糖果熊不确定的事可以先搜一下再回答，不瞎编。
 */
public class WebSearchTool implements Tool {
    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final String SEARCH_URL =
            System.getenv().getOrDefault("SEARCH_URL", "https://html.duckduckgo.com/html/");

    @Override public String getName() { return "web_search"; }

    @Override
    public String getDescription() {
        return "搜索网页。当用户问你一个你不太确定的事（新闻、知识、事实），先调这个查一下，再基于结果回答。不要瞎编。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "搜索关键词，中文英文都可以")
                ),
                "required", List.of("query"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) return "缺少搜索词";

        try {
            String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            String url = SEARCH_URL + "?q=" + encoded;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; CandyBear/1.0)")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "搜索失败 HTTP " + resp.statusCode();
            }

            List<String> results = parseDuckDuckGo(resp.body());
            if (results.isEmpty()) {
                return "未找到相关结果，换个说法试试？";
            }

            StringBuilder sb = new StringBuilder("搜索结果：\n");
            for (int i = 0; i < Math.min(results.size(), 5); i++) {
                sb.append(i + 1).append(". ").append(results.get(i)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Web search failed: {}", e.getMessage());
            return "搜索出错: " + e.getMessage();
        }
    }

    private List<String> parseDuckDuckGo(String html) {
        List<String> results = new ArrayList<>();
        // DuckDuckGo HTML: <a rel="nofollow" class="result__a" href="...">title</a>
        // followed by <a class="result__snippet">snippet</a>
        String[] blocks = html.split("class=\"result__body\"");
        for (int i = 1; i < blocks.length && results.size() < 5; i++) {
            String block = blocks[i];
            // Extract title + URL
            String title = extractBetween(block, "class=\"result__a\"", "</a>");
            title = title != null ? title.replaceAll("<[^>]+>", "").trim() : "";
            String url = extractBetween(block, "href=\"", "\"");
            // Extract snippet
            String snippet = extractBetween(block, "class=\"result__snippet\"", "</a>");
            snippet = snippet != null ? snippet.replaceAll("<[^>]+>", "").trim() : "";

            if (!title.isEmpty()) {
                results.add(title + (snippet.isEmpty() ? "" : " —— " + snippet)
                        + (url != null ? " (" + url + ")" : ""));
            }
        }
        return results;
    }

    private String extractBetween(String text, String start, String end) {
        int si = text.indexOf(start);
        if (si < 0) return null;
        si += start.length();
        // If end is </a>, skip past the > that closes the opening tag
        if (end.equals("</a>")) {
            int gt = text.indexOf(">", si);
            if (gt >= 0) si = gt + 1;
        }
        int ei = text.indexOf(end, si);
        return ei >= 0 ? text.substring(si, ei).trim() : null;
    }
}
