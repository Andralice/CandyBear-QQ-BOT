
package com.start.service;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
/**
 * 网页截图服务类
 * <p>
 * 该服务负责通过调用外部 Python 脚本 ({@code screenshot.py}) 来执行网页截图任务。
 * 主要功能包括：
 * <ul>
 *     <li>异步执行截图任务，避免阻塞主线程。</li>
 *     <li>动态生成唯一的输出文件路径，防止并发冲突。</li>
 *     <li>自动清理生成的临时图片文件，节省磁盘空间。</li>
 *     <li>捕获并处理 Python 脚本执行过程中的错误。</li>
 * </ul>
 * </p>
 * <p>
 * 注意：此服务依赖于系统中安装的 Python 环境以及位于 JAR 包同级目录下的 {@code screenshot.py} 脚本。
 * </p>
 *
 * @author Lingma
 * @version 1.0
 */
//✅截图服务（已完成）
public class WebScreenshotService {

    // ✅ 指向虚拟环境中的 Python 解释器
    private static final String PYTHON_EXECUTABLE = "/home/alice/py/bin/python";
    // ✅ 脚本放在 JAR 同级目录
    private static final String SCRIPT_NAME = "screenshot.py";

    // ✅ 动态生成唯一输出文件名，避免并发冲突
    private static String generateOutputPath() {
        return "/tmp/screenshot_" + UUID.randomUUID().toString().replace("-", "") + ".png";
    }

    /**
     * 执行截图任务
     * @param taskName 任务名（如 "kkrb-overview"）
     * @return 图片文件路径
     */
    public CompletableFuture<String> takeScreenshot(String taskName) {
        String outputPath = generateOutputPath();

        return CompletableFuture.supplyAsync(() -> {
            try {
                // ✅ 获取 JAR 所在目录（即 screenshot.py 所在目录）
                String jarDir = getJarDirectory();
                // 清理旧的 debug 文件，避免 root 覆盖不了 alice 的导致 Permission denied
                try { Runtime.getRuntime().exec(new String[]{"bash", "-c", "rm -f /tmp/debug-*.png"}).waitFor(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
                ProcessBuilder pb = new ProcessBuilder(PYTHON_EXECUTABLE, SCRIPT_NAME, taskName, outputPath);
                pb.directory(new File(jarDir)); // 在脚本所在目录执行
                // 指定 Playwright 浏览器路径（Java 以 root 跑，浏览器在 alice 目录下）
                pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", "/home/alice/.cache/ms-playwright");

                Process process = pb.start();

                // 读取标准输出（用于调试）
                BufferedReader stdout = new BufferedReader(
                        new InputStreamReader(process.getInputStream())
                );
                BufferedReader stderr = new BufferedReader(
                        new InputStreamReader(process.getErrorStream())
                );

                StringBuilder output = new StringBuilder();
                String line;
                while ((line = stdout.readLine()) != null) {
                    output.append(line).append("\n");
                }
                while ((line = stderr.readLine()) != null) {
                    output.append("[ERROR] ").append(line).append("\n");
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Python script failed with code " + exitCode +
                            ". Output: " + output.toString());
                }

                // ✅ 验证文件是否生成
                if (!Files.exists(Paths.get(outputPath))) {
                    throw new RuntimeException("Output file not created: " + outputPath);
                }

                return outputPath;
            } catch (Exception e) {
                // 清理可能残留的空文件
                try {
                    Files.deleteIfExists(Paths.get(outputPath));
                } catch (IOException ignored) {}
                throw new RuntimeException("Failed to take screenshot for task: " + taskName, e);
            }
        });
    }

    /**
     * 获取当前 JAR 文件所在目录
     */
    private String getJarDirectory() {
        try {
            String path = WebScreenshotService.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();
            File jarFile = new File(path);
            return jarFile.getParentFile().getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("Cannot determine JAR directory", e);
        }
    }

    /**
     * 安全读取图片字节（并自动清理文件）
     */
    public byte[] readAndCleanupImage(String imagePath) throws IOException {
        try {
            Path path = Paths.get(imagePath);
            byte[] bytes = Files.readAllBytes(path);
            Files.deleteIfExists(path); // ✅ 发送后立即清理
            return bytes;
        } catch (IOException e) {
            // 如果文件不存在，可能是已被清理或从未生成
            throw new IOException("Cannot read image: " + imagePath, e);
        }
    }
}