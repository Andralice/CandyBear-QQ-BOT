package com.start.service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 沙箱工作区管理。创建项目副本 → 隔离修改 → 生成 diff → 确认后应用或丢弃。
 * 仅复制源码和 pom.xml，不复制 target/ 和 .git/。
 */
public class WorkspaceManager {
    private static final Logger logger = LoggerFactory.getLogger(WorkspaceManager.class);

    private final Path projectRoot;
    private Path workspaceRoot;

    public WorkspaceManager() {
        this.projectRoot = detectProjectRoot();
    }

    public Path getProjectRoot() { return projectRoot; }

    /** 创建沙箱工作区副本 */
    public Path create() throws IOException {
        String wsName = "ws_" + System.currentTimeMillis();
        Path wsParent = projectRoot.getParent();
        if (wsParent == null) wsParent = projectRoot;
        workspaceRoot = wsParent.resolve(wsName);
        copySourceTo(workspaceRoot);
        logger.info("沙箱工作区创建: {}", workspaceRoot);
        return workspaceRoot;
    }

    /** 生成 workspace 与原始项目的统一 diff */
    public String diff() throws IOException {
        if (workspaceRoot == null || !Files.exists(workspaceRoot)) {
            return "工作区不存在";
        }
        // 用 git diff 比较 src/ 和 pom.xml
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "diff", "--no-index",
                    projectRoot.resolve("src").toString(),
                    workspaceRoot.resolve("src").toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return output.isEmpty() ? "无变更" : output;
        } catch (Exception e) {
            return "diff 生成失败: " + e.getMessage();
        }
    }

    /** 将沙箱中的修改应用到原始项目（复制 src/ + pom.xml） */
    public String apply() throws IOException {
        if (workspaceRoot == null || !Files.exists(workspaceRoot)) {
            return "工作区不存在";
        }
        // 备份原文件
        Path srcBak = projectRoot.resolve("src.bak");
        if (Files.exists(srcBak)) deleteRecursive(srcBak);
        copyDirectory(projectRoot.resolve("src"), srcBak);
        Path pomBak = projectRoot.resolve("pom.xml.bak");
        Files.copy(projectRoot.resolve("pom.xml"), pomBak, StandardCopyOption.REPLACE_EXISTING);

        // 覆盖源文件
        deleteRecursive(projectRoot.resolve("src"));
        copyDirectory(workspaceRoot.resolve("src"), projectRoot.resolve("src"));
        Files.copy(workspaceRoot.resolve("pom.xml"), projectRoot.resolve("pom.xml"),
                StandardCopyOption.REPLACE_EXISTING);

        logger.info("沙箱修改已应用到项目");
        return "已应用。备份在 src.bak/ 和 pom.xml.bak";
    }

    /** 丢弃沙箱 */
    public void discard() {
        if (workspaceRoot != null && Files.exists(workspaceRoot)) {
            try { deleteRecursive(workspaceRoot); } catch (IOException e) {
                logger.warn("清理沙箱失败: {}", e.getMessage());
            }
            logger.info("沙箱已丢弃: {}", workspaceRoot);
            workspaceRoot = null;
        }
    }

    // === 内部 ===

    private void copySourceTo(Path dest) throws IOException {
        Files.createDirectories(dest);
        // 复制 src/
        Path srcDir = projectRoot.resolve("src");
        if (Files.exists(srcDir)) {
            copyDirectory(srcDir, dest.resolve("src"));
        }
        // 复制 pom.xml
        Path pom = projectRoot.resolve("pom.xml");
        if (Files.exists(pom)) {
            Files.copy(pom, dest.resolve("pom.xml"));
        }
    }

    private static void copyDirectory(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
    }

    private static Path detectProjectRoot() {
        String cwd = System.getProperty("user.dir");
        Path cwdPath = Paths.get(cwd);
        if (Files.exists(cwdPath.resolve("pom.xml"))) {
            return cwdPath.toAbsolutePath().normalize();
        }
        Path optPath = Paths.get("/opt/qq-bot");
        if (Files.exists(optPath.resolve("pom.xml"))) {
            return optPath;
        }
        return cwdPath.toAbsolutePath().normalize();
    }
}
