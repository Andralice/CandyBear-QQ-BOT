package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.repository.AuditModelUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Shared transport wrapper for audit-model calls.
 * It records successful responses, HTTP failures, and transport exceptions without storing prompts or responses.
 */
public final class AuditModelUsageService {
    private static final Logger logger = LoggerFactory.getLogger(AuditModelUsageService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile AuditModelUsageService instance;
    private static final ThreadLocal<CallContext> CONTEXT = new ThreadLocal<>();

    /** 关联一次模型请求的业务来源；不包含提示词和响应正文。 */
    public record CallContext(String messageId, String groupId, String userId,
                              String sessionId, String functionName) {
        public static CallContext empty() {
            return new CallContext("", "", "", "", "");
        }

        public CallContext {
            messageId = clean(messageId);
            groupId = clean(groupId);
            userId = clean(userId);
            sessionId = clean(sessionId);
            functionName = clean(functionName);
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** 临时绑定当前线程的业务上下文，供同一轮内的所有文本模型调用继承。 */
    public static ContextScope pushContext(CallContext context) {
        CallContext previous = CONTEXT.get();
        CONTEXT.set(context == null ? CallContext.empty() : context);
        return () -> {
            if (previous == null) CONTEXT.remove();
            else CONTEXT.set(previous);
        };
    }

    public interface ContextScope extends AutoCloseable {
        @Override void close();
    }

    private final AuditModelUsageRepository repository;
    private final ExecutorService writer;

    private AuditModelUsageService(AuditModelUsageRepository repository) {
        this.repository = repository;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AuditUsage-Writer");
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized void init(DataSource dataSource) {
        if (instance == null) {
            instance = new AuditModelUsageService(new AuditModelUsageRepository(dataSource));
            logger.info("AuditModelUsageService 已初始化");
        }
    }

    /** Sends one OpenAI-compatible audit request and records the result. */
    public static HttpResponse<String> send(HttpClient client, HttpRequest request,
                                            String scene, String model)
            throws IOException, InterruptedException {
        return send(client, request, scene, model, 1);
    }

    /** Sends one request with an explicit retry/round number for usage accounting. */
    public static HttpResponse<String> send(HttpClient client, HttpRequest request,
                                            String scene, String model, int attempt)
            throws IOException, InterruptedException {
        return send(client, request, scene, model, CONTEXT.get(), attempt);
    }

    public static HttpResponse<String> send(HttpClient client, HttpRequest request,
                                            String scene, String model, CallContext context,
                                            int attempt)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = elapsedMs(started);
            Usage usage = Usage.from(response.body());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300
                    && !usage.hasProviderError;
            record(scene, model, request.uri(), context, attempt, response.statusCode(), success, usage,
                    latencyMs, success ? null : errorType(response.statusCode(), usage));
            return response;
        } catch (IOException | InterruptedException e) {
            record(scene, model, request.uri(), context, attempt, null, false, Usage.empty(),
                    elapsedMs(started), e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            record(scene, model, request.uri(), context, attempt, null, false, Usage.empty(),
                    elapsedMs(started), e.getClass().getSimpleName());
            throw e;
        }
    }

    /** 异步文本模型请求的统一记录入口。 */
    public static java.util.concurrent.CompletableFuture<HttpResponse<String>> sendAsync(
            HttpClient client, HttpRequest request, String scene, String model,
            CallContext context, int attempt) {
        long started = System.nanoTime();
        try {
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            Throwable cause = error instanceof java.util.concurrent.CompletionException ce
                                    && ce.getCause() != null ? ce.getCause() : error;
                            record(scene, model, request.uri(), context, attempt, null, false,
                                    Usage.empty(), elapsedMs(started), cause.getClass().getSimpleName());
                        } else {
                            Usage usage = Usage.from(response == null ? null : response.body());
                            int status = response == null ? 0 : response.statusCode();
                            boolean success = response != null && status >= 200 && status < 300
                                    && !usage.hasProviderError;
                            record(scene, model, request.uri(), context, attempt, response == null ? null : status,
                                    success, usage, elapsedMs(started),
                                    success ? null : errorType(status, usage));
                        }
                    });
        } catch (RuntimeException e) {
            record(scene, model, request.uri(), context, attempt, null, false, Usage.empty(),
                    elapsedMs(started), e.getClass().getSimpleName());
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
    }

    public static void shutdown() {
        AuditModelUsageService inst = instance;
        if (inst == null) return;
        inst.writer.shutdown();
        try {
            if (!inst.writer.awaitTermination(5, TimeUnit.SECONDS)) inst.writer.shutdownNow();
        } catch (InterruptedException e) {
            inst.writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String errorType(int status, Usage usage) {
        if (usage.hasProviderError) return "provider_error";
        return "HTTP_" + status;
    }

    private static void record(String scene, String model, URI endpoint, CallContext context, int attempt,
                               Integer status, boolean success, Usage usage, long latencyMs,
                               String errorType) {
        AuditModelUsageService inst = instance;
        if (inst == null) return;
        String host = endpoint == null ? null : endpoint.getHost();
        String errorMessage = errorType;
        int cacheDenominator = usage.inputTokens + (usage.cachedTokensAreSeparate ? usage.cachedInputTokens : 0);
        Double hitRate = cacheDenominator > 0
                ? Math.min(100.0, (usage.cachedInputTokens * 100.0) / cacheDenominator) : null;
        CallContext ctx = context == null ? CONTEXT.get() : context;
        if (ctx == null) ctx = CallContext.empty();
        String functionName = scene == null || scene.isBlank() ? ctx.functionName() : scene;
        final CallContext finalContext = ctx;
        final String finalFunctionName = functionName;
        logger.info("model_usage scene={} model={} function={} messageId={} groupId={} userId={} sessionId={} status={} success={} attempt={} inputTokens={} outputTokens={} cachedTokens={} cacheHitRate={} latencyMs={} errorType={}",
                scene, model, functionName, ctx.messageId(), ctx.groupId(), ctx.userId(), ctx.sessionId(),
                status, success, attempt, usage.inputTokens, usage.outputTokens,
                usage.cachedInputTokens, hitRate == null ? "n/a" : String.format("%.2f%%", hitRate),
                latencyMs, errorType == null ? "" : errorType);
        try {
            inst.writer.execute(() -> {
                try {
                    inst.repository.insert(scene, model, host, finalContext.messageId(), finalContext.groupId(),
                            finalContext.userId(), finalContext.sessionId(), finalFunctionName, attempt, status, success,
                            usage.inputTokens, usage.outputTokens, usage.totalTokens,
                            usage.cachedInputTokens, hitRate, latencyMs, errorType, errorMessage,
                            LocalDateTime.now());
                } catch (Exception e) {
                    logger.warn("审计 token 用量写入失败 scene={}: {}", scene, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            logger.debug("审计 token 用量线程已关闭，丢弃记录 scene={}", scene);
        }
    }

    private static final class Usage {
        final int inputTokens;
        final int outputTokens;
        final int totalTokens;
        final int cachedInputTokens;
        final boolean cachedTokensAreSeparate;
        final boolean hasProviderError;

        private Usage(int inputTokens, int outputTokens, int totalTokens,
                      int cachedInputTokens, boolean cachedTokensAreSeparate,
                      boolean hasProviderError) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
            this.cachedInputTokens = cachedInputTokens;
            this.cachedTokensAreSeparate = cachedTokensAreSeparate;
            this.hasProviderError = hasProviderError;
        }

        static Usage empty() { return new Usage(0, 0, 0, 0, false, false); }

        static Usage from(String body) {
            if (body == null || body.isBlank()) return empty();
            try {
                JsonNode root = JSON.readTree(body);
                JsonNode usage = root.path("usage");
                if (!usage.isObject()) usage = root.path("billing_usage").path("openai_usage");
                if (!usage.isObject()) usage = JSON.createObjectNode();
                int input = firstInt(usage, "input_tokens", "prompt_tokens");
                int output = firstInt(usage, "output_tokens", "completion_tokens");
                int total = usage.path("total_tokens").asInt(input + output);
                int cached = firstInt(usage, "cached_tokens", "cache_read_input_tokens");
                cached = Math.max(cached, nestedInt(usage, "input_tokens_details", "cached_tokens"));
                cached = Math.max(cached, nestedInt(usage, "prompt_tokens_details", "cached_tokens"));
                boolean separate = usage.has("cache_read_input_tokens")
                        || usage.has("cache_creation_input_tokens");
                return new Usage(input, output, total, cached, separate, root.has("error"));
            } catch (Exception e) {
                return empty();
            }
        }

        private static int firstInt(JsonNode node, String first, String second) {
            if (node.has(first)) return node.path(first).asInt(0);
            return node.path(second).asInt(0);
        }

        private static int nestedInt(JsonNode node, String parent, String child) {
            return node.path(parent).path(child).asInt(0);
        }
    }
}
