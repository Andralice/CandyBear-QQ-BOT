package com.start.agent;

import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自我进化工具 —— 糖果熊修改自己的 Java 源代码、编译、重启。
 *
 * 流程: git 备份 → 精确替换旧代码 → 写入新代码 → mvn 编译 → 失败则回滚
 */
public class SelfEvolveTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SelfEvolveTool.class);

    private final String realUserId;
    private final Path projectRoot;

    private static final int COMPILE_TIMEOUT_SECONDS = 120;

    public SelfEvolveTool() {
        this.realUserId = "0";
        this.projectRoot = detectProjectRoot();
    }

    public SelfEvolveTool(String realUserId) {
        this.realUserId = realUserId;
        this.projectRoot = detectProjectRoot();
    }

    private static Path detectProjectRoot() {
        // 尝试从当前工作目录或 classpath 推断项目根目录
        String cwd = System.getProperty("user.dir");
        Path cwdPath = Paths.get(cwd);
        if (Files.exists(cwdPath.resolve("pom.xml"))) {
            return cwdPath.toAbsolutePath().normalize();
        }
        // 回退: 假设在 /opt/qq-bot 或项目目录
        Path optPath = Paths.get("/opt/qq-bot");
        if (Files.exists(optPath.resolve("pom.xml"))) {
            return optPath;
        }
        return cwdPath.toAbsolutePath().normalize();
    }

    @Override
    public String getName() { return "self_evolve"; }

    @Override
    public String getDescription() {
        return "修改自己的 Java 源代码并编译验证，用于自我迭代。\n" +
               "【重要】调用前必须先用 shell_exec cat 读取目标文件，确认 old_snippet 的精确内容。\n" +
               "参数: target_file(相对于项目根目录的文件路径, 例: src/main/java/com/start/service/BaiLianService.java)\n" +
               "old_snippet(文件中要替换的精确代码片段, 必须与文件内容完全一致, 含缩进和换行)\n" +
               "new_snippet(替换后的新代码片段, 缩进需与原文一致)\n" +
               "reason(一句话说明为什么改)。\n" +
               "流程: git提交备份 → 替换代码 → mvn编译 → 编译失败自动回滚(文件和git都回滚)。\n" +
               "如果返回'未找到old_snippet'，说明代码片段不匹配，请重新cat文件确认。\n" +
               "仅管理员(归儿)可用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "target_file", Map.of("type", "string",
                                "description", "要修改的文件，相对于项目根目录的路径。例如: src/main/java/com/start/service/BaiLianService.java"),
                        "old_snippet", Map.of("type", "string",
                                "description", "文件中要被替换的精确代码片段。必须与文件中的内容完全一致（包括空格/缩进/换行）。不能是模糊匹配。"),
                        "new_snippet", Map.of("type", "string",
                                "description", "替换后的新代码片段。注意缩进需与 old_snippet 一致。"),
                        "reason", Map.of("type", "string",
                                "description", "为什么要做这个修改，用一两句话说明"),
                        "push_to_git", Map.of("type", "boolean",
                                "description", "编译成功后是否 git push 到远程仓库触发 CI/CD 自动部署。默认 false。")
                ),
                "required", List.of("target_file", "old_snippet", "new_snippet", "reason"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        // ---- 权限检查 ----
        long uid;
        try {
            uid = Long.parseLong(realUserId);
        } catch (NumberFormatException e) {
            return "无法确定用户身份";
        }
        if (uid != BotConfig.getAdminQq()) {
            return "自我进化功能仅对归儿开放。";
        }

        String targetFile = (String) args.get("target_file");
        String oldSnippet = (String) args.get("old_snippet");
        String newSnippet = (String) args.get("new_snippet");
        String reason = (String) args.get("reason");

        if (targetFile == null || targetFile.isBlank()) return "请指定 target_file";
        if (oldSnippet == null || oldSnippet.isBlank()) return "请指定 old_snippet";
        if (newSnippet == null) newSnippet = "";
        if (reason == null || reason.isBlank()) return "请填写 reason";

        // ---- 安全检查：禁止修改配置文件 ----
        String normalized = targetFile.replace('\\', '/');
        if (normalized.contains(".properties") || normalized.contains(".env") ||
                normalized.contains("credentials") || normalized.contains("BotConfig.java") ||
                normalized.contains("CommandPolicy.java")) {
            return "禁止修改配置文件或安全策略文件: " + targetFile;
        }

        Path filePath = projectRoot.resolve(targetFile).normalize();
        if (!filePath.startsWith(projectRoot)) {
            return "文件路径不合法（试图访问项目外文件）: " + targetFile;
        }

        if (!Files.exists(filePath)) {
            return "文件不存在: " + filePath;
        }

        try {
            // ---- Step 1: 读取原始文件 ----
            String originalContent = Files.readString(filePath);

            // ---- Step 2: 验证 old_snippet 存在且唯一 ----
            int count = countOccurrences(originalContent, oldSnippet);
            if (count == 0) {
                return "在文件中未找到 old_snippet。请确认代码片段与文件内容完全一致（包括缩进和换行）。\n" +
                       "文件: " + targetFile;
            }
            if (count > 1) {
                return "old_snippet 在文件中出现了 " + count + " 次，不够精确。请扩大 old_snippet 包含更多上下文使其唯一。\n" +
                       "文件: " + targetFile;
            }

            // ---- Step 3: 备份（文件 .bak + git 可选） ----
            Path bakPath = filePath.resolveSibling(filePath.getFileName() + ".bak");
            Files.copy(filePath, bakPath, StandardCopyOption.REPLACE_EXISTING);

            try {
                runCommand("git", "add", "-A");
                runCommand("git", "commit", "-m", "backup: before self-evolve — " + reason);
            } catch (Exception e) {
                logger.debug("git 备份跳过（可能不在 git 仓库中）");
            }

            // ---- Step 4: 写入新内容 ----
            String newContent = originalContent.replace(oldSnippet, newSnippet);
            Files.writeString(filePath, newContent);
            logger.info("已修改文件: {}", targetFile);

            // ---- Step 5: 打包 ----
            String[] mvnBase = getMvnCommand();
            String[] packageCmd = new String[mvnBase.length + 3];
            System.arraycopy(mvnBase, 0, packageCmd, 0, mvnBase.length);
            packageCmd[mvnBase.length] = "package";
            packageCmd[mvnBase.length + 1] = "-DskipTests";
            packageCmd[mvnBase.length + 2] = "-q";
            String buildOutput = runCommandWithTimeout(packageCmd);

            if (buildOutput == null) {
                revertFile(filePath, originalContent);
                return "打包超时（>" + COMPILE_TIMEOUT_SECONDS + "秒），已回滚。";
            }

            if (buildOutput.contains("BUILD FAILURE") || buildOutput.contains("ERROR")) {
                revertFile(filePath, originalContent);
                try { runCommand("git", "checkout", "--", targetFile); } catch (Exception ignored) {}
                String shortError = buildOutput.length() > 1500
                        ? buildOutput.substring(0, 1500) + "\n... [截断]" : buildOutput;
                return "编译失败，已自动回滚。\n\n编译错误:\n" + shortError;
            }

            // ---- Step 6: 部署 JAR（生产服务器） ----
            String jarName = detectJarName();
            Path targetJar = projectRoot.resolve("target").resolve(jarName);
            String os = System.getProperty("os.name", "").toLowerCase();

            if (Files.exists(targetJar)) {
                if (!os.contains("win")) {
                    // Linux 服务器: 备份旧 JAR + 替换为新 JAR
                    Path serverJar = Paths.get("/opt/qq-bot", jarName);
                    if (Files.exists(serverJar)) {
                        Path backupJar = Paths.get("/opt/qq-bot", jarName + ".bak." +
                                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
                        Files.copy(serverJar, backupJar);
                    }
                    Files.copy(targetJar, serverJar, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("JAR 已部署到: {}", serverJar);
                }
            } else {
                return "打包成功但未找到 JAR 文件: " + targetJar;
            }

            // ---- Step 7: 清理 ----
            Files.deleteIfExists(bakPath);
            logger.info("自我进化成功: {} — {}", targetFile, reason);

            // ---- Step 7: Git Push（可选，触发 CI/CD） ----
            String pushResult = "";
            Object pushObj = args.get("push_to_git");
            boolean shouldPush = pushObj instanceof Boolean b && b;
            if (shouldPush) {
                try {
                    runCommand("git", "add", targetFile);
                    runCommand("git", "commit", "-m", "self-evolve: " + reason);
                    // 始终推到 auto-evolve 分支，触发 GitHub Actions
                    runCommand("git", "push", "origin", "HEAD:auto-evolve");
                    pushResult = "\n已 push 到 origin/auto-evolve。GitHub Actions 将自动构建部署。";
                } catch (Exception e) {
                    pushResult = "\ngit push 失败: " + e.getMessage() + "。请检查 git remote 和认证是否配置。";
                }
            }

            return "自我进化成功！\n" +
                   "文件: " + targetFile + "\n" +
                   "原因: " + reason + "\n" +
                   "编译+打包: 通过\n" +
                   (os.contains("win") ? "JAR 在 target/ 目录。" + pushResult
                           : "新 JAR 已部署到 /opt/qq-bot/。" + pushResult + " 或使用 restart_bot 重启。");

        } catch (Exception e) {
            logger.error("自我进化失败", e);
            try {
                Path bakPath = filePath.resolveSibling(filePath.getFileName() + ".bak");
                if (Files.exists(bakPath)) {
                    Files.move(bakPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ignored) {}
            return "自我进化过程异常: " + e.getMessage();
        }
    }

    private void revertFile(Path filePath, String originalContent) {
        try {
            Files.writeString(filePath, originalContent);
            logger.info("已回滚文件: {}", filePath);
        } catch (IOException e) {
            logger.error("回滚文件失败: {}", filePath, e);
        }
    }

    private int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String runCommand(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            return output;
        } catch (Exception e) {
            logger.warn("命令执行失败: {}", String.join(" ", cmd), e);
            return "";
        }
    }

    private String runCommandWithTimeout(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null; // timeout
            }
            return new String(p.getInputStream().readAllBytes());
        } catch (Exception e) {
            logger.warn("命令执行失败: {}", String.join(" ", cmd), e);
            return "执行异常: " + e.getMessage();
        }
    }

    private String detectJarName() {
        // 从 pom.xml 读取 artifactId 和 version 拼接 JAR 名
        Path pomPath = projectRoot.resolve("pom.xml");
        try {
            String pom = Files.readString(pomPath);
            String artifactId = extractXmlTag(pom, "artifactId");
            String version = extractXmlTag(pom, "version");
            if (artifactId != null && !artifactId.isBlank()) {
                if (version == null || version.isBlank()) version = "1.0-SNAPSHOT";
                // 如果 version 包含 ${...} 占位符，用默认值
                if (version.contains("${")) version = "1.0-SNAPSHOT";
                return artifactId.trim() + "-" + version.trim() + ".jar";
            }
        } catch (Exception e) {
            logger.debug("无法从 pom.xml 读取 JAR 名，使用默认值");
        }
        return "untitled-1.0-SNAPSHOT.jar";
    }

    private String extractXmlTag(String xml, String tagName) {
        Pattern p = Pattern.compile("<" + tagName + ">([^<]*)</" + tagName + ">");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private String[] getMvnCommand() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new String[]{"cmd", "/c", "mvn"};
        }
        return new String[]{"mvn"};
    }
}
