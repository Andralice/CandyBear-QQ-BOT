package com.start.agent;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 命令安全策略：白名单/需确认/黑名单。
 * 所有命令即使通过 AI 生成，也必须经过此策略检查。
 * 仅管理员可执行，策略在 ServerAdminService 层兜底。
 */
public class CommandPolicy {

    public enum Verdict {
        ALLOW,          // 安全，直接执行
        NEED_CONFIRM,   // 需二次确认
        DENY            // 禁止执行
    }

    // ── 白名单：安全只读命令 ──
    private static final Set<String> WHITE_PREFIX = Set.of(
            "ps ", "top ", "htop", "free ", "df ", "du ",
            "uptime", "uname ", "who", "w ", "last",
            "netstat ", "ss ", "ip ", "ifconfig",
            "cat ", "head ", "tail ", "less ", "more ",
            "ls ", "find ", "locate ", "which ", "whereis ",
            "grep ", "wc ", "sort ", "uniq ", "cut ", "awk ", "sed ",
            "echo ", "date", "env", "printenv",
            "systemctl status ", "journalctl ", "docker ps", "docker logs",
            "git log", "git diff", "git status", "git branch", "git show",
            "git remote ", "git config ",
            "pgrep ", "pidof ",
            "curl ", "wget ",
            "file ", "stat ", "md5sum ", "sha",
            "java -version", "mvn --version", "node --version", "python --version",
            "crontab -l"
    );

    // ── 需确认：有写操作的命令 ──
    private static final Set<String> CONFIRM_PREFIX = Set.of(
            "systemctl restart ", "systemctl stop ", "systemctl start ",
            "systemctl reload ", "systemctl enable ", "systemctl disable ",
            "kill ", "pkill ", "killall ",
            "reboot", "shutdown", "poweroff",
            "git pull", "git fetch", "git push", "git merge", "git rebase",
            "git checkout ", "git reset ", "git stash ",
            "mvn ", "./deploy.sh", "bash deploy.sh",
            "docker restart ", "docker stop ", "docker start ",
            "cp ", "mv ", "rm ", "touch ", "mkdir ", "rmdir ",
            "chmod ", "chown ", "chattr",
            "tar ", "zip ", "unzip", "gzip", "gunzip",
            "tee ", "dd ",
            "crontab -e", "crontab ",
            "apt ", "yum ", "pip ", "npm ", "npx "
    );

    // ── 黑名单：绝对不能执行 ──
    private static final Set<String> BLACK_PREFIX = Set.of(
            "rm -rf /", "rm -rf /*", "rm -rf ~", "rm -rf .*",
            "mkfs.", "mkswap",
            "> /dev/sda", "> /dev/hda", "dd if=",
            ":(){ :|:& };:",  // fork bomb
            "chmod 777 /", "chmod -R 777 /",
            "> /etc/passwd", "> /etc/shadow",
            "iptables -F", "ufw disable",
            "git push --force origin main", "git push -f origin main",
            "docker rm -f", "docker system prune",
            "passwd", "useradd", "userdel", "usermod",
            "mount ", "umount ", "fdisk", "parted",
            "alias ", "unalias",
            "export ", "source ", ". ",
            "eval ", "exec ",
            "nc ", "ncat ", "telnet ",
            "scp ", "rsync ", "sftp ",
            "chroot"
    );

    // ── 敏感文件模式：即使是 cat/head/tail 也要拒绝 ──
    private static final List<Pattern> SENSITIVE_FILE_PATTERNS = List.of(
            // 配置文件含密钥
            Pattern.compile(".*(cat|head|tail|less|more)\\s+.*(\\.env|application\\.properties|credentials|secrets|id_rsa|id_ed25519|private\\.key).*"),
            // 系统敏感文件
            Pattern.compile(".*(cat|head|tail)\\s+/etc/(shadow|passwd|sudoers).*"),
            // SSH key 目录
            Pattern.compile(".*(cat|head|tail)\\s+.*\\.ssh/.*"),
            // 数据库 dump
            Pattern.compile(".*(cat|head|tail)\\s+.*\\.(sql|dump|sqlite).*")
    );

    // ── 危险字符模式：命令注入/拼接检测 ──
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // 管道到 shell 执行
            Pattern.compile(".*\\|\\s*(sh|bash|zsh|dash|python|perl|ruby|lua|node)(\\s|$).*"),
            // 管道到 sudo
            Pattern.compile(".*\\|\\s*sudo.*"),
            // 反引号命令替换
            Pattern.compile(".*`[^`]+`.*"),
            // $() 命令替换
            Pattern.compile(".*\\$\\([^)]+\\).*"),
            // ; 后接危险命令
            Pattern.compile(".*;\\s*(sh|bash|reboot|poweroff|shutdown|rm|dd|mkfs|chmod|chown).*"),
            // && 后接危险命令
            Pattern.compile(".*&&\\s*(sh|bash|reboot|poweroff|shutdown|rm|dd|mkfs|chmod|chown).*"),
            // || 后接危险命令
            Pattern.compile(".*\\|\\|\\s*(sh|bash|reboot|poweroff|shutdown|rm|dd).*"),
            // 重定向覆盖关键路径（/dev/null 除外，它只是丢弃输出）
            Pattern.compile(".*>\\s*/(etc|dev/(?!null)|proc|sys|boot)/.*"),
            // 追加重定向到关键路径（/dev/null 除外）
            Pattern.compile(".*>>\\s*/(etc|dev/(?!null)|proc|sys|boot)/.*"),
            // wget/curl 管道到 shell
            Pattern.compile(".*(wget|curl).*\\|\\s*(sh|bash).*"),
            // 从 /dev/null 读取然后操作危险路径
            Pattern.compile(".*/dev/null.*/(etc|var|usr|opt|home).*"),
            // 十六进制/Base64 编码执行（常见绕过手法）
            Pattern.compile(".*\\$\\\\x[0-9a-fA-F]{2}.*"),
            // IFS 环境变量操纵（shell 注入绕过）
            Pattern.compile(".*\\$\\{IFS.*\\}.*"),
            // 换行符注入（URL 编码的 %0a 在 shell 中变成换行）
            Pattern.compile(".*%0[ad].*"),
            // xargs 执行任意命令
            Pattern.compile(".*xargs\\s+(sh|bash|rm|dd).*"),
            // find -exec 执行任意命令
            Pattern.compile(".*find\\s+.*-exec\\s+(sh|bash|rm).*")
    );

    /**
     * 判断命令的安全等级。
     */
    public static Verdict evaluate(String command, long userId, long adminQq) {
        // 非管理员直接拒绝（ServerAdminService 层也检查，这里双保险）
        if (userId != adminQq) {
            return Verdict.DENY;
        }

        String cmd = command.trim();
        if (cmd.isEmpty()) return Verdict.DENY;

        // 0. 检测空字节注入（C 字符串截断）
        if (cmd.contains("\0")) {
            return Verdict.DENY;
        }

        // 1. 检测危险模式（正则）
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(cmd).matches()) {
                return Verdict.DENY;
            }
        }

        // 2. 检测敏感文件读取
        for (Pattern p : SENSITIVE_FILE_PATTERNS) {
            if (p.matcher(cmd).matches()) {
                return Verdict.DENY;
            }
        }

        // 3. 检查黑名单前缀
        for (String prefix : BLACK_PREFIX) {
            if (cmd.startsWith(prefix)) {
                return Verdict.DENY;
            }
        }
        // rm -rf 危险变体
        if (cmd.contains("rm -rf") && (cmd.contains("/") || cmd.contains("*") || cmd.contains("."))) {
            return Verdict.DENY;
        }
        // chmod 777 危险变体
        if (cmd.contains("chmod") && cmd.contains("777") && cmd.contains("/")) {
            return Verdict.DENY;
        }
        // 重定向覆盖（通用的 > 检测，排除 echo 和 /dev/null 等无害情况）
        if (cmd.matches(".*[^e]\\s*>\\s*/.*") && !cmd.startsWith("echo ") && !cmd.matches(".*>\\s*/dev/null(\\s|$).*")) {
            return Verdict.DENY;
        }

        // 4. 检查需确认前缀
        for (String prefix : CONFIRM_PREFIX) {
            if (cmd.startsWith(prefix)) {
                return Verdict.NEED_CONFIRM;
            }
        }

        // 5. 检查白名单前缀
        for (String prefix : WHITE_PREFIX) {
            if (cmd.startsWith(prefix)) {
                return Verdict.ALLOW;
            }
        }

        // 6. 未知命令默认拒绝
        return Verdict.DENY;
    }

    /**
     * 返回 verdict 的中文说明。
     */
    public static String describe(Verdict v) {
        return switch (v) {
            case ALLOW -> "✅ 安全命令";
            case NEED_CONFIRM -> "⚠️ 需要二次确认";
            case DENY -> "🚫 已拦截（不在白名单或命中危险模式）";
        };
    }
}
