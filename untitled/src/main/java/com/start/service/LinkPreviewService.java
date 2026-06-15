package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链接预览服务 —— 获取网页的 OG 元数据 / title / description
 */
public class LinkPreviewService {

    private static final Logger logger = LoggerFactory.getLogger(LinkPreviewService.class);

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int REQUEST_TIMEOUT_SECONDS = 8;

    // SSRF 防护：禁止访问的内网 CIDR 前缀
    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "127.", "10.", "0.",
            "192.168.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "169.254."
    );

    /**
     * 获取链接内容摘要，返回格式化的文本供 AI 上下文使用
     */
    public String fetchPreview(String url) {
        if (isBlockedUrl(url)) {
            return "";
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; CandyBearBot/1.0)")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            if (!contentType.contains("text/html") && !contentType.contains("text/plain")) {
                return "[非网页链接] " + url;
            }

            String html = resp.body();
            if (html == null || html.isBlank()) return "";

            // 只取前 200KB 防止 OOM
            if (html.length() > 200_000) {
                html = html.substring(0, 200_000);
            }

            String title = extractMeta(html, "og:title");
            if (title == null) title = extractTitle(html);
            String description = extractMeta(html, "og:description");
            if (description == null) description = extractMeta(html, "description");

            StringBuilder sb = new StringBuilder();
            sb.append("【链接信息】URL: ").append(url);
            if (title != null && !title.isBlank()) {
                sb.append(" | 标题: ").append(title.trim());
            }
            if (description != null && !description.isBlank()) {
                String desc = description.trim();
                if (desc.length() > 300) desc = desc.substring(0, 300) + "...";
                sb.append(" | 摘要: ").append(desc);
            }
            return sb.toString();

        } catch (Exception e) {
            logger.debug("获取链接预览失败: {} — {}", url, e.getMessage());
            return "【链接】" + url;
        }
    }

    // ---- 内网检测 ----

    private boolean isBlockedUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return true;
            // localhost
            if ("localhost".equalsIgnoreCase(host) || host.startsWith("[")) return true;
            InetAddress addr = InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                return true;
            }
            for (String prefix : BLOCKED_PREFIXES) {
                if (ip.startsWith(prefix)) return true;
            }
            return false;
        } catch (UnknownHostException e) {
            return true;
        }
    }

    // ---- HTML 解析（正则，无外部依赖） ----

    private String extractMeta(String html, String property) {
        // <meta property="og:title" content="xxx" />
        Pattern p = Pattern.compile(
                "<meta[^>]+property=[\"']" + Pattern.quote(property) + "[\"'][^>]+content=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(1);

        // 反向属性顺序
        p = Pattern.compile(
                "<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+property=[\"']" + Pattern.quote(property) + "[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        m = p.matcher(html);
        if (m.find()) return m.group(1);

        // <meta name="description" content="xxx" />
        p = Pattern.compile(
                "<meta[^>]+name=[\"']" + Pattern.quote(property) + "[\"'][^>]+content=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        m = p.matcher(html);
        if (m.find()) return m.group(1);

        // 反向 name
        p = Pattern.compile(
                "<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+name=[\"']" + Pattern.quote(property) + "[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        m = p.matcher(html);
        if (m.find()) return m.group(1);

        return null;
    }

    private String extractTitle(String html) {
        Pattern p = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (m.find()) {
            String title = m.group(1).trim();
            return title.isEmpty() ? null : title;
        }
        return null;
    }
}
