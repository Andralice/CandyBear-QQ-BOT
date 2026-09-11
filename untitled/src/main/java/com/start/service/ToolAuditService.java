package com.start.service;

import com.start.model.ToolAuditLog;
import com.start.repository.ToolAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 工具调用审计服务。
 * 工具调用埋点调用 record()，本服务异步写库，不阻塞业务。
 * BotBootstrap 在启动时 init() 注入仓储。
 */
public class ToolAuditService {

    private static final Logger logger = LoggerFactory.getLogger(ToolAuditService.class);

    private static final int ARGS_MAX = 500;
    private static final int RESULT_MAX = 1000;
    private static final int ERROR_MAX = 1000;

    private static volatile ToolAuditService instance;
    private final ToolAuditLogRepository repo;
    private final ExecutorService executor;

    private ToolAuditService(ToolAuditLogRepository repo) {
        this.repo = repo;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ToolAudit-Writer");
            t.setDaemon(true);
            return t;
        });
    }

    /** BotBootstrap 启动时调用一次。 */
    public static synchronized void init(ToolAuditLogRepository repo) {
        if (instance == null) {
            instance = new ToolAuditService(repo);
            logger.info("ToolAuditService 已初始化");
        }
    }

    public static ToolAuditService getInstance() {
        return instance;
    }

    /** 关停后台线程，BotBootstrap 关闭时调用。 */
    public static void shutdown() {
        ToolAuditService inst = instance;
        if (inst != null) {
            inst.executor.shutdown();
            try {
                if (!inst.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    inst.executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                inst.executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 记录一次工具调用。线程安全，异步写库。
     *
     * @param toolName     工具名
     * @param userId       调用者用户 ID
     * @param groupId      群号（私聊时为 null）
     * @param sessionId    会话 ID
     * @param argsJson     工具参数 JSON
     * @param result       工具返回结果
     * @param rejected     是否被授权层拒绝
     * @param success      是否执行成功
     * @param errorMessage 失败原因（成功时为 null）
     * @param latencyMs    执行耗时毫秒
     */
    public void record(String toolName, String userId, String groupId, String sessionId,
                       String argsJson, String result, boolean rejected, boolean success,
                       String errorMessage, long latencyMs) {
        if (instance == null) {
            // 未初始化（如某些测试场景），静默丢弃
            return;
        }
        ToolAuditLog log = new ToolAuditLog();
        log.setToolName(toolName);
        log.setCallerUserId(userId);
        log.setGroupId(groupId);
        log.setSessionId(sessionId);
        log.setArgsSummary(truncate(redactPrivacyCode(toolName, argsJson), ARGS_MAX));
        log.setResultSummary(truncate(redactPrivacyCode(toolName, result), RESULT_MAX));
        log.setRejected(rejected);
        log.setSuccess(success);
        log.setErrorMessage(truncate(errorMessage, ERROR_MAX));
        log.setLatencyMs(latencyMs);
        log.setCreatedAt(LocalDateTime.now());

        try {
            executor.execute(() -> {
                try {
                    repo.insert(log);
                } catch (Exception e) {
                    logger.warn("审计写入失败 tool={}: {}", toolName, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            logger.debug("审计线程池已关，丢弃记录 tool={}", toolName);
        }
    }

    /** 工具静态方法，未初始化时静默丢弃（避免 NPE）。 */
    public static void recordStatic(String toolName, String userId, String groupId, String sessionId,
                                    String argsJson, String result, boolean rejected, boolean success,
                                    String errorMessage, long latencyMs) {
        ToolAuditService inst = instance;
        if (inst == null) return;
        inst.record(toolName, userId, groupId, sessionId, argsJson, result, rejected, success, errorMessage, latencyMs);
    }

    static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    static String redactPrivacyCode(String toolName, String value) {
        if (!"delete_my_profile".equals(toolName) || value == null) return value;
        return value.replaceAll("(\\\"confirmation_code\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")", "$1[REDACTED]$2")
                .replaceAll("(?i)(个人画像\\s+)[A-Z0-9]{8}", "$1[REDACTED]")
                .replaceAll("(?i)(验证码\\s*)[A-Z0-9]{8}", "$1[REDACTED]");
    }
}
