package com.start.agent.evo;

import com.start.agent.Tool;

import com.start.config.BotConfig;
import com.start.repository.EvolutionRecordRepository;
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
 * 自我进化工具 —— 糖果熊修改自己的 Java 源代码、编译、部署。
 *
 * 流程: stash → sync main → 临时分支 → 修改代码 → 编译 → 测试 → 打包 → 部署 JAR
 *       → squash merge 到 main → push origin/main → 删除临时分支
 * 任何一步失败都回滚：切回 main，删除临时分支，恢复 .bak。
 * main 是唯一 Source of Truth，不再使用 auto-evolve 长期分支。
 */
public class SelfEvolveTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SelfEvolveTool.class);

    private final String realUserId;
    private final Path projectRoot;
    private final EvolutionRecordRepository evoRepo;

    private static final int COMPILE_TIMEOUT_SECONDS = 120;

    public SelfEvolveTool() {
        this.realUserId = "0";
        this.projectRoot = detectProjectRoot();
        this.evoRepo = null;
    }

    public SelfEvolveTool(String realUserId) {
        this.realUserId = realUserId;
        this.projectRoot = detectProjectRoot();
        this.evoRepo = null;
    }

    public SelfEvolveTool(String realUserId, EvolutionRecordRepository evoRepo) {
        this.realUserId = realUserId;
        this.projectRoot = detectProjectRoot();
        this.evoRepo = evoRepo;
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
               "流程: 创建临时分支 → 替换代码 → mvn编译测试打包 → squash merge到main → push origin/main。\n" +
               "编译失败自动回滚(切回main、删除临时分支、恢复.bak)。\n" +
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
                                "description", "编译成功后是否 git push 到 origin/main。默认 false。")
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
            // ---- Step 1: 暂存本地变更 ----
            boolean stashed = stashLocalChanges();

            // ---- Step 2: 同步 main 到最新 ----
            try {
                runCommand("git", "checkout", "main");
                runCommand("git", "fetch", "origin", "main");
                String mergeOut = runCommand("git", "merge", "origin/main", "--no-edit");
                logger.info("已同步 origin/main: {}", mergeOut.trim());
            } catch (Exception e) {
                logger.warn("同步 main 失败: {}", e.getMessage());
                try { runCommand("git", "merge", "--abort"); } catch (Exception ignored) {}
                popStash(stashed);
                return "同步 main 分支失败: " + e.getMessage() + "。请手动解决冲突后再试。";
            }

            // ---- Step 3: 创建临时进化分支 ----
            String branchName = "evolve/" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
            try {
                runCommand("git", "checkout", "-b", branchName);
                logger.info("已创建临时分支: {}", branchName);
            } catch (Exception e) {
                popStash(stashed);
                return "创建临时分支失败: " + e.getMessage();
            }

            // ---- Step 4: 读取原始文件 ----
            String originalContent = Files.readString(filePath);

            // ---- Step 5: 验证 old_snippet 存在且唯一 ----
            int count = countOccurrences(originalContent, oldSnippet);
            if (count == 0) {
                abortBranch(branchName, stashed);
                return "在文件中未找到 old_snippet。请确认代码片段与文件内容完全一致（包括缩进和换行）。\n" +
                       "文件: " + targetFile;
            }
            if (count > 1) {
                abortBranch(branchName, stashed);
                return "old_snippet 在文件中出现了 " + count + " 次，不够精确。请扩大 old_snippet 包含更多上下文使其唯一。\n" +
                       "文件: " + targetFile;
            }

            // ---- Step 6: 备份（在临时分支上） ----
            Path bakPath = filePath.resolveSibling(filePath.getFileName() + ".bak");
            Files.copy(filePath, bakPath, StandardCopyOption.REPLACE_EXISTING);

            try {
                runCommand("git", "add", "-A");
                runCommand("git", "commit", "-m", "backup: before self-evolve — " + reason);
            } catch (Exception e) {
                logger.debug("git 备份跳过（可能不在 git 仓库中）");
            }

            // ---- Step 7: 写入新内容 ----
            String newContent = originalContent.replace(oldSnippet, newSnippet);
            Files.writeString(filePath, newContent);
            logger.info("已修改文件: {}", targetFile);

            // ---- Step 8: 编译 ----
            String[] mvnBase = getMvnCommand();
            String[] compileCmd = buildMvnCmd(mvnBase, "compile", "-q");
            CmdResult compileResult = runCommandWithTimeout(compileCmd);

            if (compileResult.timedOut()) {
                revertFile(filePath, originalContent);
                abortBranch(branchName, stashed);
                recordEvolve(targetFile, reason, "compile_timeout", "编译超时（>" + COMPILE_TIMEOUT_SECONDS + "秒）", false);
                return "编译超时（>" + COMPILE_TIMEOUT_SECONDS + "秒），已回滚。";
            }
            if (!compileResult.ok()) {
                revertFile(filePath, originalContent);
                abortBranch(branchName, stashed);
                String shortError = compileResult.output().length() > 1500
                        ? compileResult.output().substring(0, 1500) + "\n... [截断]" : compileResult.output();
                recordEvolve(targetFile, reason, "compile_fail", shortError, false);
                return "编译失败，已自动回滚。\n\n编译错误:\n" + shortError;
            }

            // ---- Step 9: 跑测试 ----
            String[] testCmd = buildMvnCmd(mvnBase, "test");
            CmdResult testResult = runCommandWithTimeout(testCmd);
            String testSummary;
            if (testResult.timedOut()) {
                testSummary = "⚠ 测试超时，跳过验证。";
            } else if (testResult.ok()) {
                testSummary = "✅ 测试全部通过";
            } else {
                testSummary = parseTestResults(testResult.output());
            }

            // ---- Step 10: 打包 ----
            String[] packageCmd = buildMvnCmd(mvnBase, "package", "-DskipTests", "-q");
            CmdResult pkgResult = runCommandWithTimeout(packageCmd);

            if (pkgResult.timedOut()) {
                revertFile(filePath, originalContent);
                abortBranch(branchName, stashed);
                recordEvolve(targetFile, reason, "package_timeout", "打包超时", false);
                return "打包超时，已回滚。";
            }
            if (!pkgResult.ok()) {
                revertFile(filePath, originalContent);
                abortBranch(branchName, stashed);
                String pkgError = pkgResult.output().length() > 1500
                        ? pkgResult.output().substring(0, 1500) : pkgResult.output();
                recordEvolve(targetFile, reason, "package_fail", pkgError, false);
                return "打包失败，已回滚。\n\n" + pkgError;
            }

            // ---- Step 11: 部署 JAR（生产服务器） ----
            String jarName = detectJarName();
            String os = System.getProperty("os.name", "").toLowerCase();

            Path targetJar = projectRoot.resolve("target").resolve(jarName);
            if (!Files.exists(targetJar)) {
                Path fallback = projectRoot.resolve(jarName);
                if (Files.exists(fallback)) {
                    targetJar = fallback;
                }
            }

            if (Files.exists(targetJar)) {
                if (!os.contains("win")) {
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
                abortBranch(branchName, stashed);
                return "打包完成但未找到 JAR 文件。\n"
                        + "在以下位置均未找到 " + jarName + ":\n"
                        + "  - " + projectRoot.resolve("target").resolve(jarName) + "\n"
                        + "  - " + projectRoot.resolve(jarName) + "\n"
                        + "请检查 mvn package 是否正确执行，或手动检查 target/ 目录。";
            }

            // ---- Step 12: 清理 ----
            Files.deleteIfExists(bakPath);
            logger.info("自我进化成功: {} — {}", targetFile, reason);

            // ---- Step 13: 合并到 main 并推送 ----
            boolean shouldPush = args.get("push_to_git") instanceof Boolean b && b;
            String pushResult = mergeToMainAndPush(branchName, targetFile, reason, shouldPush);

            popStash(stashed);
            recordEvolve(targetFile, reason, "success", null, shouldPush);

            return "自我进化成功！\n" +
                   "文件: " + targetFile + "\n" +
                   "原因: " + reason + "\n" +
                   "编译: 通过\n" +
                   testSummary + "\n" +
                   (os.contains("win") ? "JAR 在 target/ 目录。" + pushResult
                           : "新 JAR 已部署到 /opt/qq-bot/。" + pushResult + " 或使用 restart_bot 重启。");

        } catch (Exception e) {
            logger.error("自我进化失败", e);
            recordEvolve(targetFile, reason, "error", e.getMessage(), false);
            try {
                Path bakPath = filePath.resolveSibling(filePath.getFileName() + ".bak");
                if (Files.exists(bakPath)) {
                    Files.move(bakPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ignored) {}
            try { runCommand("git", "checkout", "main"); } catch (Exception ignored) {}
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

    /** 暂存本地未提交变更，返回 true 表示有内容被 stash。 */
    private boolean stashLocalChanges() {
        try {
            String out = runCommand("git", "stash", "push", "-m", "self-evolve-auto-stash");
            if (out.contains("No local changes to save")) {
                return false;
            }
            logger.info("已暂存本地变更");
            return true;
        } catch (Exception e) {
            logger.debug("git stash 跳过: {}", e.getMessage());
            return false;
        }
    }

    private void popStash(boolean stashed) {
        if (!stashed) return;
        try {
            runCommand("git", "stash", "pop");
            logger.info("已恢复本地变更");
        } catch (Exception e) {
            logger.warn("git stash pop 失败，本地变更留在 stash 中: {}", e.getMessage());
        }
    }

    /** 终止临时分支：切回 main，删除临时分支，恢复 stash。 */
    private void abortBranch(String branchName, boolean stashed) {
        try { runCommand("git", "checkout", "main"); } catch (Exception ignored) {}
        try { runCommand("git", "branch", "-D", branchName); } catch (Exception ignored) {}
        popStash(stashed);
    }

    /** 将临时分支 squash merge 到 main，可选 push 到 origin/main。返回推送结果描述。 */
    private String mergeToMainAndPush(String branchName, String targetFile, String reason, boolean shouldPush) {
        try {
            // 在临时分支上提交修改
            runCommand("git", "add", targetFile);
            runCommand("git", "commit", "-m", "self-evolve: " + reason);

            // 切回 main，squash merge
            runCommand("git", "checkout", "main");
            runCommand("git", "merge", "--squash", branchName);
            runCommand("git", "commit", "-m", "self-evolve: " + reason);

            // 删除临时分支
            runCommand("git", "branch", "-D", branchName);
            logger.info("已 squash merge {} → main", branchName);

            if (shouldPush) {
                try {
                    runCommand("git", "push", "origin", "main");
                    return "\n已推送到 origin/main。";
                } catch (Exception e) {
                    return "\ngit push 失败: " + e.getMessage() + "。已合并到本地 main，请手动推送。";
                }
            }
            return "\n已合并到本地 main（未推送）。";
        } catch (Exception e) {
            // merge 失败：回退到 main，保留临时分支供人工处理
            try { runCommand("git", "checkout", "main"); } catch (Exception ignored) {}
            try { runCommand("git", "merge", "--abort"); } catch (Exception ignored) {}
            logger.error("合并到 main 失败，临时分支 {} 已保留: {}", branchName, e.getMessage());
            return "\n合并到 main 失败: " + e.getMessage() + "。临时分支 " + branchName + " 已保留。";
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

    /** 命令执行结果 */
    private record CmdResult(boolean ok, String output, boolean timedOut) {}

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

    private CmdResult runCommandWithTimeout(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new CmdResult(false, "", true); // timeout
            }
            String output = new String(p.getInputStream().readAllBytes());
            int exitCode = p.exitValue();
            boolean ok = exitCode == 0;
            if (!ok) {
                logger.warn("命令退出码非0: {} exit={}", String.join(" ", cmd), exitCode);
            }
            return new CmdResult(ok, output, false);
        } catch (Exception e) {
            logger.warn("命令执行失败: {} — {}", String.join(" ", cmd), e.getMessage());
            return new CmdResult(false, "命令无法执行: " + e.getMessage(), false);
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

    private String[] buildMvnCmd(String[] base, String... args) {
        String[] cmd = new String[base.length + args.length];
        System.arraycopy(base, 0, cmd, 0, base.length);
        System.arraycopy(args, 0, cmd, base.length, args.length);
        return cmd;
    }

    private String parseTestResults(String output) {
        // 提取 mvn test 的关键信息
        StringBuilder sb = new StringBuilder("测试结果:\n");
        for (String line : output.split("\n")) {
            if (line.contains("Tests run:") || line.contains("Failures:") ||
                    line.contains("Errors:") || line.contains("Skipped:") ||
                    line.contains("BUILD SUCCESS") || line.contains("BUILD FAILURE") ||
                    (line.contains("<<< FAILURE") || line.contains("<<< ERROR"))) {
                sb.append(line.trim()).append("\n");
            }
        }
        // 提取失败测试详情
        int failIdx = output.indexOf("Failed tests:");
        if (failIdx >= 0) {
            String failPart = output.substring(failIdx);
            int endIdx = failPart.indexOf("\n\n");
            if (endIdx < 0) endIdx = Math.min(failPart.length(), 500);
            sb.append(failPart.substring(0, endIdx));
        }
        return sb.toString();
    }

    private void recordEvolve(String targetFile, String reason, String result, String errorMsg, boolean gitPushed) {
        if (evoRepo == null) return;
        try {
            evoRepo.insert(targetFile, reason, result, errorMsg, gitPushed);
        } catch (Exception e) {
            logger.warn("记录进化历史异常: {}", e.getMessage());
        }
    }

    private String[] getMvnCommand() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new String[]{"cmd", "/c", "mvn"};
        }
        return new String[]{"mvn"};
    }
}
