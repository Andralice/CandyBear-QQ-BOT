package com.start.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.model.KkrbGameData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * kkrb.net 数据抓取服务。
 * 调用 screenshot.py --extract 模式，获取结构化 JSON 数据而非截图。
 */
public class KkrbScraperService {

    private static final Logger logger = LoggerFactory.getLogger(KkrbScraperService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PYTHON_EXECUTABLE = "/home/alice/py/bin/python";
    private static final String SCRIPT_NAME = "screenshot.py";
    private static final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "KkrbScraper");
        t.setDaemon(true);
        return t;
    });
    private static final int TIMEOUT_SECONDS = 30;

    private static volatile String cachedJarDir;

    private static String getJarDir() {
        if (cachedJarDir != null) return cachedJarDir;
        try {
            String path = KkrbScraperService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            cachedJarDir = new File(path).getParentFile().getAbsolutePath();
        } catch (Exception e) {
            cachedJarDir = ".";
            logger.warn("无法确定 JAR 目录，使用当前目录: {}", e.toString());
        }
        return cachedJarDir;
    }

    /**
     * 抓取 kkrb.net 指定页面的结构化数据。
     * @param taskName 任务名，如 kkrb-overview / kkrb-overview-2 / kkrb-overview-3
     * @return KkrbGameData
     */
    public CompletableFuture<KkrbGameData> fetch(String taskName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String jarDir = getJarDir();
                ProcessBuilder pb = new ProcessBuilder(
                        PYTHON_EXECUTABLE, SCRIPT_NAME, "--extract", taskName);
                pb.directory(new File(jarDir));
                pb.environment().put("PLAYWRIGHT_BROWSERS_PATH",
                        "/home/alice/.cache/ms-playwright");

                Process process = pb.start();

                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();
                try (BufferedReader outReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                     BufferedReader errReader = new BufferedReader(
                             new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = outReader.readLine()) != null) stdout.append(line).append("\n");
                    while ((line = errReader.readLine()) != null) stderr.append(line).append("\n");
                }

                boolean completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    throw new RuntimeException("数据提取超时 (" + TIMEOUT_SECONDS + "s): " + taskName);
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    throw new RuntimeException("Python 脚本退出码 " + exitCode
                            + " stderr: " + stderr.toString());
                }

                String jsonStr = stdout.toString().trim();
                if (jsonStr.isEmpty()) {
                    return createError("数据提取返回空结果");
                }

                logger.debug("提取 {} 成功，stderr: {}", taskName, stderr.toString().trim());
                return MAPPER.readValue(jsonStr, KkrbGameData.class);

            } catch (Exception e) {
                logger.error("抓取 {} 失败: {}", taskName, e.toString());
                return createError(e.getMessage());
            }
        }, executor);
    }

    private KkrbGameData createError(String msg) {
        KkrbGameData data = new KkrbGameData();
        data.text = "❌ " + msg;
        return data;
    }
}
