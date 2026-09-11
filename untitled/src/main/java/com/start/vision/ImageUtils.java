package com.start.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Base64;

public class ImageUtils {

    private static final Logger logger = LoggerFactory.getLogger(ImageUtils.class);
    private static final int MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024; // 10MB

    public static BufferedImage downloadImage(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            return ImageIO.read(url);
        } catch (IOException e) {
            logger.warn("下载图片失败: {}", imageUrl, e);
            return null;
        }
    }

    public static BufferedImage resize(BufferedImage img, int width, int height) {
        if (img == null) return null;
        Image tmp = img.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(tmp, 0, 0, null);
        g2d.dispose();
        return resized;
    }

    /**
     * 下载图片原始字节，通过 HttpURLConnection（支持重定向、超时）
     */
    public static byte[] downloadImageBytes(String imageUrl) {
        try {
            URL url = URI.create(imageUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int status = conn.getResponseCode();
            if (status < 200 || status >= 400) {
                logger.warn("下载图片 HTTP {}: {}", status, imageUrl);
                conn.disconnect();
                return null;
            }

            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        logger.warn("图片过大(>10MB)，截断: {}", imageUrl);
                        break;
                    }
                    bos.write(buf, 0, n);
                }
                conn.disconnect();
                return bos.toByteArray();
            }
        } catch (Exception e) {
            logger.warn("下载图片异常: {}", imageUrl, e);
            return null;
        }
    }

    /**
     * 下载图片并转为 base64 data URI，供多模态 LLM 使用
     */
    public static String downloadImageAsBase64DataUri(String imageUrl) {
        byte[] bytes = downloadImageBytes(imageUrl);
        if (bytes == null || bytes.length == 0) return null;
        String mime = detectMimeType(bytes, imageUrl);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        return "data:" + mime + ";base64," + b64;
    }

    static String detectMimeType(byte[] bytes, String url) {
        if (bytes != null) {
            if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
                return "image/gif";
            }
            if (bytes.length >= 8
                    && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                    && bytes[3] == 'G' && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                    && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) {
                return "image/png";
            }
            if (bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                    && (bytes[2] & 0xFF) == 0xFF) {
                return "image/jpeg";
            }
            if (bytes.length >= 12
                    && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
                return "image/webp";
            }
            if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
                return "image/bmp";
            }
            try (InputStream input = new ByteArrayInputStream(bytes)) {
                String detected = URLConnection.guessContentTypeFromStream(input);
                if (detected != null && detected.startsWith("image/")) return detected;
            } catch (IOException ignored) {
                // fall back to URL suffix below
            }
        }
        return detectMimeTypeFromUrl(url);
    }

    private static String detectMimeTypeFromUrl(String url) {
        if (url == null) return "image/jpeg";
        String lower = url.toLowerCase();
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".gif")) return "image/gif";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}
