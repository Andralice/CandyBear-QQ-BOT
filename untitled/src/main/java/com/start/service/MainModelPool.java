package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 主模型高可用候选池。
 *
 * <p>候选项不可变，候选列表和当前线路通过原子引用替换。一次生成只读取一次候选快照，
 * 因此后台探测、故障转移和配置热重载都只影响后续请求，不会拆开正在进行的工具循环。</p>
 */
public final class MainModelPool implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MainModelPool.class);
    private static final long BASE_FAILURE_COOLDOWN_MS = 30_000L;
    private static final long MAX_FAILURE_COOLDOWN_MS = 300_000L;
    private static final int SWITCH_HYSTERESIS_PERCENT = 10;
    private static final long MANUAL_SELECTION_SLOW_WARNING_MS = 4_000L;
    private static final long HARD_SLOW_RESPONSE_MS = 15_000L;
    private static final long SLOW_RESPONSE_MS = 8_000L;
    private static final int SLOW_MULTIPLIER_PERCENT = 180;

    private final AtomicReference<List<Candidate>> candidates;
    private final AtomicReference<Candidate> active;
    private final AtomicReference<Candidate> manuallySelected = new AtomicReference<>();
    private final ConcurrentHashMap<String, CandidateHealth> health = new ConcurrentHashMap<>();
    private final boolean poolEnabled;
    private final int probeCooldownMs;
    private final int probeTimeoutMs;
    private final ExecutorService probeExecutor;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);
    private final AtomicBoolean forcedProbePending = new AtomicBoolean(false);
    private final LongAdder probeRequests = new LongAdder();
    private final LongAdder probeInputTokens = new LongAdder();
    private final LongAdder probeOutputTokens = new LongAdder();
    private volatile String appliedSpec;
    private volatile long lastProbeStartedAtMs;
    private volatile Prober prober;
    private volatile Supplier<String> candidateSpecSupplier;
    private volatile Function<String, String> credentialResolver;

    MainModelPool(List<Candidate> candidates, boolean poolEnabled,
                  int probeIntervalMs, int probeTimeoutMs) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("主模型池至少需要一条线路");
        }
        List<Candidate> initial = List.copyOf(candidates);
        this.candidates = new AtomicReference<>(initial);
        this.active = new AtomicReference<>(initial.get(0));
        this.poolEnabled = poolEnabled;
        this.probeCooldownMs = Math.max(1_000, probeIntervalMs);
        this.probeTimeoutMs = Math.max(1_000, probeTimeoutMs);
        this.probeExecutor = Executors.newFixedThreadPool(8, daemonFactory("main-model-probe"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("main-model-pool"));
    }

    /** 从环境变量引用配置构建池；未启用或配置无效时保留原有单模型线路。 */
    public static MainModelPool fromConfig(Candidate fallback, boolean enabled, String rawSpec,
                                           int probeIntervalMs, int probeTimeoutMs,
                                           Function<String, String> credentialResolver) {
        Objects.requireNonNull(fallback, "fallback");
        if (!enabled) {
            return new MainModelPool(List.of(fallback), false, probeIntervalMs, probeTimeoutMs);
        }
        List<Candidate> configured = parseCandidates(rawSpec, credentialResolver);
        if (configured.isEmpty()) {
            // 保留热重载能力：候选配置修复后无需重启进程。
            return new MainModelPool(List.of(fallback), true, probeIntervalMs, probeTimeoutMs);
        }
        MainModelPool pool = new MainModelPool(configured, true, probeIntervalMs, probeTimeoutMs);
        pool.appliedSpec = normalizeSpec(rawSpec);
        return pool;
    }

    /**
     * 解析格式：
     * {@code id|base_url|model|protocol|api_key_env[|tier=N][|thinking=disabled];...}
     *
     * <p>第 6 列及以后是可选项；tier 默认是 1，数字越小优先级越高。
     * 最后一列只能写环境变量名，永远不接受密钥明文。</p>
     */
    static List<Candidate> parseCandidates(String rawSpec,
                                            Function<String, String> credentialResolver) {
        if (rawSpec == null || rawSpec.isBlank()) return List.of();
        Objects.requireNonNull(credentialResolver, "credentialResolver");

        List<Candidate> parsed = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String[] entries = rawSpec.split("[;\\r\\n]+");
        for (String rawEntry : entries) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) continue;

            String[] fields = entry.split("\\|", -1);
            if (fields.length < 5 || fields.length > 7) {
                logger.warn("忽略主模型池配置项：需要至少 5 个字段，可追加 tier=N 和 thinking=disabled");
                continue;
            }

            String id = fields[0].trim();
            String baseUrl = fields[1].trim();
            String model = fields[2].trim();
            String protocol = fields[3].trim();
            String keyEnv = fields[4].trim();
            int tier = 1;
            boolean tierSpecified = false;
            boolean thinkingDisabled = false;
            boolean optionsValid = true;
            for (int i = 5; i < fields.length; i++) {
                String option = fields[i].trim();
                if (option.isEmpty()) {
                    optionsValid = false;
                    break;
                }
                if ("thinking=disabled".equalsIgnoreCase(option)) {
                    if (thinkingDisabled) {
                        optionsValid = false;
                        break;
                    }
                    thinkingDisabled = true;
                    continue;
                }
                if (option.regionMatches(true, 0, "tier=", 0, 5)) {
                    if (tierSpecified) {
                        optionsValid = false;
                        break;
                    }
                    try {
                        tier = Integer.parseInt(option.substring(5).trim());
                    } catch (NumberFormatException e) {
                        optionsValid = false;
                    }
                    if (tier < 1) optionsValid = false;
                    tierSpecified = true;
                    continue;
                }
                optionsValid = false;
                break;
            }
            if (id.isEmpty() || model.isEmpty() || !isHttpUrl(baseUrl)
                    || !isSupportedProtocol(protocol) || !isEnvironmentName(keyEnv)
                    || !optionsValid
                    || !ids.add(id)) {
                logger.warn("忽略主模型池无效配置项 id={}", id.isEmpty() ? "<empty>" : id);
                continue;
            }

            String apiKey;
            try {
                apiKey = credentialResolver.apply(keyEnv);
            } catch (RuntimeException e) {
                apiKey = "";
            }
            if (apiKey == null || apiKey.isBlank()) {
                logger.warn("忽略主模型池线路 {}：环境变量 {} 未配置", id, keyEnv);
                continue;
            }

            parsed.add(Candidate.fromBaseUrl(id, baseUrl, model, protocol, keyEnv, apiKey,
                    thinkingDisabled, tier));
        }
        return List.copyOf(parsed);
    }

    /** 热替换候选列表。当前线路仍存在时保留其 id，否则原子切到新的首选线路。 */
    public synchronized boolean reloadCandidates(String rawSpec, Function<String, String> credentialResolver) {
        if (!poolEnabled) return false;
        String normalizedSpec = normalizeSpec(rawSpec);
        if (normalizedSpec.equals(appliedSpec)) return false;
        List<Candidate> replacement = parseCandidates(rawSpec, credentialResolver);
        if (replacement.isEmpty()) {
            logger.warn("拒绝热重载空的主模型池配置，继续使用现有 {} 条线路", candidates().size());
            return false;
        }

        List<Candidate> previous = candidates();
        if (sameConfiguration(previous, replacement)) return false;

        Candidate oldActive = active.get();
        Candidate oldManual = manuallySelected.get();
        Candidate newManual = oldManual == null ? null : replacement.stream()
                .filter(candidate -> candidate.id().equals(oldManual.id()) && candidate.sameRoute(oldManual))
                .findFirst().orElse(null);
        Candidate newActive = newManual != null ? newManual : replacement.stream()
                .filter(candidate -> candidate.id().equals(oldActive.id()))
                .findFirst()
                .orElseGet(() -> chooseBestAvailable(replacement, null));

        for (Candidate candidate : replacement) {
            Candidate old = previous.stream()
                    .filter(existing -> existing.id().equals(candidate.id()))
                    .findFirst()
                    .orElse(null);
            if (old == null || !old.sameRoute(candidate)) health.remove(candidate.id());
        }
        candidates.set(replacement);
        manuallySelected.set(newManual);
        active.set(newActive);
        appliedSpec = normalizedSpec;
        Set<String> liveIds = new HashSet<>();
        replacement.forEach(candidate -> liveIds.add(candidate.id()));
        health.keySet().retainAll(liveIds);
        logger.info("主模型池已热重载: {} -> {} 条线路，active={}",
                previous.size(), replacement.size(), newActive.id());
        requestProbe("candidate reload", true);
        return true;
    }

    public Candidate snapshot() {
        return active.get();
    }

    public boolean isManuallySelected() {
        return manuallySelected.get() != null;
    }

    public long latencyScoreMs(Candidate candidate) {
        Candidate live = findCurrent(candidate);
        return live == null ? Long.MAX_VALUE : healthFor(live).latencyScore();
    }

    /** 指定候选做一次实时探测；成功且慢时须由管理员二次确认才会锁定。 */
    public ManualSelectionResult selectManually(String candidateId, boolean confirmSlow) {
        Candidate candidate = candidates().stream()
                .filter(item -> item.id().equals(candidateId))
                .findFirst().orElse(null);
        if (candidate == null) {
            return new ManualSelectionResult(ManualSelectionStatus.NOT_FOUND, null, 0, "");
        }
        Prober currentProber = prober;
        if (currentProber == null) {
            return new ManualSelectionResult(ManualSelectionStatus.NOT_READY, candidate, 0, "probe unavailable");
        }

        long startedAt = System.nanoTime();
        ProbeResult result;
        try {
            result = currentProber.probe(candidate, probeTimeoutMs);
        } catch (Exception e) {
            result = ProbeResult.failed(candidate, elapsedMs(startedAt), e.getClass().getSimpleName());
        }
        probeRequests.increment();
        if (result == null) {
            result = ProbeResult.failed(candidate, elapsedMs(startedAt), "empty probe result");
        }
        probeInputTokens.add(Math.max(0, result.inputTokens()));
        probeOutputTokens.add(Math.max(0, result.outputTokens()));

        return completeManualSelection(candidate, result, confirmSlow);
    }

    private synchronized ManualSelectionResult completeManualSelection(
            Candidate candidate, ProbeResult result, boolean confirmSlow) {
        Candidate live = findCurrent(candidate);
        if (live == null || !live.sameRoute(candidate)) {
            return new ManualSelectionResult(ManualSelectionStatus.CONFIG_CHANGED, candidate,
                    result.latencyMs(), "candidate configuration changed");
        }
        if (!result.healthy()) {
            reportFailure(live, result.error());
            return new ManualSelectionResult(ManualSelectionStatus.UNAVAILABLE, live,
                    result.latencyMs(), concise(result.error()));
        }

        healthFor(live).probeSuccess(result.latencyMs());
        if (result.latencyMs() > MANUAL_SELECTION_SLOW_WARNING_MS && !confirmSlow) {
            return new ManualSelectionResult(ManualSelectionStatus.SLOW_CONFIRMATION_REQUIRED, live,
                    result.latencyMs(), "");
        }
        manuallySelected.set(live);
        Candidate previous = active.getAndSet(live);
        logger.info("管理员手动锁定主模型线路: {} -> {}，探测延迟 {}ms，确认慢线路={}",
                previous.id(), live.id(), result.latencyMs(), confirmSlow);
        return new ManualSelectionResult(ManualSelectionStatus.SELECTED, live, result.latencyMs(), "");
    }

    /** 解除手动锁定，先按已知健康度恢复自动线路，再触发新一轮探测。 */
    public synchronized Candidate clearManualSelection() {
        manuallySelected.set(null);
        Candidate automatic = chooseBestAvailable(candidates(), null);
        if (automatic != null) active.set(automatic);
        requestProbe("manual selection released", true);
        return active.get();
    }

    public List<Candidate> candidates() {
        return candidates.get();
    }

    public boolean isEnabled() {
        return poolEnabled && candidates().size() > 1;
    }

    public int probeTimeoutMs() {
        return probeTimeoutMs;
    }

    /** 当前线路优先，其余线路按健康状态、探测延迟和配置顺序排列。 */
    public List<Candidate> attemptOrder() {
        List<Candidate> snapshot = candidates();
        List<Candidate> ordered = new ArrayList<>(snapshot);
        Candidate current = active.get();
        long now = System.currentTimeMillis();
        ordered.sort(Comparator
                .comparingInt((Candidate candidate) -> healthFor(candidate).available(now) ? 0 : 1)
                .thenComparingInt(Candidate::tier)
                .thenComparingInt(candidate -> candidate.id().equals(current.id()) ? 0 : 1)
                .thenComparingLong(candidate -> healthFor(candidate).latencyScore())
                .thenComparingInt(snapshot::indexOf));
        return List.copyOf(ordered);
    }

    /** 真实业务请求成功，清除该线路的失败冷却；不覆盖后台刚完成的更快线路切换。 */
    public void reportSuccess(Candidate candidate) {
        Candidate live = findCurrent(candidate);
        if (live == null || !live.sameRoute(candidate)) return;
        healthFor(live).runtimeSuccess();
    }

    /** 记录真实首轮完整响应延迟；达到原有 15 秒硬阈值才自动切换。 */
    public synchronized boolean reportSuccess(Candidate candidate, long latencyMs) {
        Candidate live = findCurrent(candidate);
        if (live == null || !live.sameRoute(candidate)) return false;
        boolean slow = healthFor(live).runtimeSuccess(latencyMs);
        boolean manuallyPinned = isPinned(live);
        if (latencyMs >= HARD_SLOW_RESPONSE_MS && !manuallyPinned) {
            reportFailure(live, "complete response took " + latencyMs + "ms");
            return true;
        }
        if (slow && !manuallyPinned) requestProbe("slow route " + live.id() + " " + latencyMs + "ms", false);
        return slow;
    }

    /** 真实业务请求失败；立即冷却该线路并让后续请求切到下一条可用线路。 */
    public synchronized Candidate reportFailure(Candidate candidate, String error) {
        Candidate live = findCurrent(candidate);
        if (live == null || !live.sameRoute(candidate)) return active.get();
        healthFor(live).failure();
        Candidate pin = manuallySelected.get();
        if (pin != null && pin.sameRoute(live)) manuallySelected.compareAndSet(pin, null);
        requestProbe("failed route " + live.id(), false);
        Candidate current = active.get();
        if (!current.id().equals(live.id())) return current;

        Candidate next = chooseBestAvailable(candidates(), live.id());
        if (next != null && !next.id().equals(live.id())) {
            active.compareAndSet(current, next);
            logger.warn("主模型池线路失败并切换: {} -> {}，原因={}",
                    live.id(), next.id(), concise(error));
        }
        return active.get();
    }

    /** 启动时探测一次；后续只由慢响应、失败或配置变化触发。 */
    public void start(Prober prober) {
        start(prober, null, null);
    }

    /** 每轮探测前读取候选配置；配置变化时热替换，不影响正在生成的请求。 */
    public void start(Prober prober, Supplier<String> candidateSpecSupplier,
                      Function<String, String> credentialResolver) {
        Objects.requireNonNull(prober, "prober");
        if (!started.compareAndSet(false, true)) return;
        this.prober = prober;
        this.candidateSpecSupplier = candidateSpecSupplier;
        this.credentialResolver = credentialResolver;
        if (poolEnabled) requestProbe("startup", true);
    }

    /** 提交一次后台全池短探测；正常触发受冷却限制，启动和配置变化可强制。 */
    boolean requestProbe(String reason, boolean force) {
        Prober currentProber = prober;
        if (!poolEnabled || currentProber == null) return false;
        long now = System.currentTimeMillis();
        if (!force && now - lastProbeStartedAtMs < probeCooldownMs) return false;
        if (!probeInFlight.compareAndSet(false, true)) {
            if (force) forcedProbePending.set(true);
            return false;
        }
        lastProbeStartedAtMs = now;
        try {
            scheduler.execute(() -> {
                try {
                    Supplier<String> specSupplier = this.candidateSpecSupplier;
                    Function<String, String> resolver = this.credentialResolver;
                    if (specSupplier != null && resolver != null) {
                        reloadCandidates(specSupplier.get(), resolver);
                    }
                    logger.info("主模型池开始短探测: reason={}, candidates={}", reason, candidates().size());
                    selectFastest(probeNow(currentProber));
                } catch (Exception e) {
                    logger.warn("主模型池探测轮次失败: {}", e.getMessage());
                } finally {
                    probeInFlight.set(false);
                    if (forcedProbePending.getAndSet(false)) {
                        requestProbe("pending candidate reload", true);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            probeInFlight.set(false);
            forcedProbePending.set(false);
            return false;
        }
        return true;
    }

    /** 立即并发探测全部线路，返回结果顺序与该轮候选快照一致。 */
    List<ProbeResult> probeNow(Prober prober) {
        List<Candidate> roundCandidates = candidates();
        List<CompletableFuture<ProbeResult>> futures = new ArrayList<>();
        for (Candidate candidate : roundCandidates) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                long startedAt = System.nanoTime();
                try {
                    ProbeResult result = prober.probe(candidate, probeTimeoutMs);
                    if (result == null) {
                        return ProbeResult.failed(candidate, elapsedMs(startedAt), "empty probe result");
                    }
                    if (result.candidate() == candidate) return result;
                    return result.healthy()
                            ? ProbeResult.healthy(candidate, result.latencyMs(),
                                    result.inputTokens(), result.outputTokens())
                            : ProbeResult.failed(candidate, result.latencyMs(), result.error());
                } catch (Exception e) {
                    return ProbeResult.failed(candidate, elapsedMs(startedAt), e.getClass().getSimpleName());
                }
            }, probeExecutor));
        }

        List<ProbeResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<ProbeResult> future : futures) {
            results.add(future.join());
        }
        return List.copyOf(results);
    }

    /** 更新健康分数并选择最快线路；新线路至少快 10% 才切换。 */
    synchronized Candidate selectFastest(List<ProbeResult> results) {
        Candidate current = active.get();
        Candidate fastest = null;
        int fastestTier = Integer.MAX_VALUE;
        long fastestScore = Long.MAX_VALUE;
        boolean currentHealthy = false;
        int healthyCount = 0;
        int roundInputTokens = 0;
        int roundOutputTokens = 0;

        if (results != null) {
            for (ProbeResult result : results) {
                if (result == null) continue;
                Candidate live = findCurrent(result.candidate());
                if (live == null || !live.sameRoute(result.candidate())) continue;

                probeRequests.increment();
                roundInputTokens += Math.max(0, result.inputTokens());
                roundOutputTokens += Math.max(0, result.outputTokens());
                CandidateHealth candidateHealth = healthFor(live);
                if (!result.healthy()) {
                    candidateHealth.failure();
                    continue;
                }

                healthyCount++;
                candidateHealth.probeSuccess(result.latencyMs());
                long score = candidateHealth.latencyScore();
                if (live.id().equals(current.id())) currentHealthy = true;
                if (fastest == null || live.tier() < fastestTier
                        || (live.tier() == fastestTier && (score < fastestScore
                        || (score == fastestScore && live.id().equals(current.id()))))) {
                    fastest = live;
                    fastestTier = live.tier();
                    fastestScore = score;
                }
            }
        }

        probeInputTokens.add(roundInputTokens);
        probeOutputTokens.add(roundOutputTokens);

        Candidate pin = manuallySelected.get();
        if (pin != null) {
            Candidate livePin = findCurrent(pin);
            ProbeResult pinResult = results == null ? null : results.stream()
                    .filter(result -> result != null && result.candidate() != null
                            && result.candidate().id().equals(pin.id()))
                    .findFirst().orElse(null);
            if (livePin == null || !livePin.sameRoute(pin)
                    || (pinResult != null && !pinResult.healthy())) {
                manuallySelected.compareAndSet(pin, null);
            } else {
                active.set(livePin);
                logger.info("主模型池探测保留管理员手动线路: {}", livePin.id());
                return livePin;
            }
        }

        if (fastest != null && !fastest.id().equals(current.id())) {
            long currentScore = healthFor(current).latencyScore();
            boolean higherPriorityTier = fastest.tier() < current.tier();
            boolean materiallyFaster = higherPriorityTier || !currentHealthy || currentScore == Long.MAX_VALUE
                    || (fastest.tier() == current.tier()
                    && fastestScore * 100L <= currentScore * (100L - SWITCH_HYSTERESIS_PERCENT));
            if (materiallyFaster) {
                active.set(fastest);
                logger.info("主模型池切换线路: {}(tier={}) -> {}(tier={})，平滑探测延迟 {}ms",
                        current.id(), current.tier(), fastest.id(), fastest.tier(), fastestScore);
            }
        }

        logger.info("主模型池探测完成: healthy={}/{}, inputTokens={}, outputTokens={}, active={}",
                healthyCount, results == null ? 0 : results.size(),
                roundInputTokens, roundOutputTokens, active.get().id());
        return active.get();
    }

    public ProbeUsage probeUsage() {
        return new ProbeUsage(probeRequests.sum(), probeInputTokens.sum(), probeOutputTokens.sum());
    }

    private Candidate chooseBestAvailable(List<Candidate> source, String excludedId) {
        long now = System.currentTimeMillis();
        return source.stream()
                .filter(candidate -> excludedId == null || !candidate.id().equals(excludedId))
                .min(Comparator
                        .comparingInt((Candidate candidate) -> healthFor(candidate).available(now) ? 0 : 1)
                        .thenComparingInt(Candidate::tier)
                        .thenComparingLong(candidate -> healthFor(candidate).latencyScore())
                        .thenComparingInt(source::indexOf))
                .orElse(source.isEmpty() ? null : source.get(0));
    }

    private Candidate findCurrent(Candidate candidate) {
        if (candidate == null) return null;
        return candidates().stream()
                .filter(current -> current.id().equals(candidate.id()))
                .findFirst()
                .orElse(null);
    }

    private CandidateHealth healthFor(Candidate candidate) {
        return health.computeIfAbsent(candidate.id(), ignored -> new CandidateHealth());
    }

    private static boolean sameConfiguration(List<Candidate> left, List<Candidate> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).sameRoute(right.get(i))) return false;
        }
        return true;
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static boolean isSupportedProtocol(String value) {
        return value != null && Set.of("openai", "openai-compatible", "anthropic", "claude", "claude-native")
                .contains(value.trim().toLowerCase());
    }

    private static boolean isEnvironmentName(String value) {
        return value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static String normalizeSpec(String rawSpec) {
        return rawSpec == null ? "" : rawSpec.trim();
    }

    private static String concise(String error) {
        if (error == null || error.isBlank()) return "unknown";
        String normalized = error.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        probeExecutor.shutdownNow();
    }

    @FunctionalInterface
    public interface Prober {
        ProbeResult probe(Candidate candidate, int timeoutMs) throws Exception;
    }

    public record ProbeResult(Candidate candidate, boolean healthy, long latencyMs, String error,
                              int inputTokens, int outputTokens) {
        public static ProbeResult healthy(Candidate candidate, long latencyMs) {
            return healthy(candidate, latencyMs, 0, 0);
        }

        public static ProbeResult healthy(Candidate candidate, long latencyMs,
                                          int inputTokens, int outputTokens) {
            return new ProbeResult(candidate, true, Math.max(0, latencyMs), "",
                    Math.max(0, inputTokens), Math.max(0, outputTokens));
        }

        public static ProbeResult failed(Candidate candidate, long latencyMs, String error) {
            return new ProbeResult(candidate, false, Math.max(0, latencyMs),
                    error == null ? "" : error, 0, 0);
        }
    }

    public record ProbeUsage(long requests, long inputTokens, long outputTokens) {}

    public enum ManualSelectionStatus {
        SELECTED, SLOW_CONFIRMATION_REQUIRED, UNAVAILABLE, NOT_FOUND, NOT_READY, CONFIG_CHANGED
    }

    public record ManualSelectionResult(ManualSelectionStatus status, Candidate candidate,
                                        long latencyMs, String error) {}

    private boolean isPinned(Candidate candidate) {
        Candidate pin = manuallySelected.get();
        return pin != null && pin.sameRoute(candidate);
    }

    private static final class CandidateHealth {
        private long latencyScore = Long.MAX_VALUE;
        private long runtimeLatencyScore = Long.MAX_VALUE;
        private int consecutiveFailures;
        private int consecutiveSlowResponses;
        private long cooldownUntilMs;

        synchronized void probeSuccess(long latencyMs) {
            long sample = Math.max(0, latencyMs);
            latencyScore = latencyScore == Long.MAX_VALUE
                    ? sample
                    : (latencyScore * 7L + sample * 3L) / 10L;
            consecutiveFailures = 0;
            cooldownUntilMs = 0;
        }

        synchronized void runtimeSuccess() {
            consecutiveFailures = 0;
            cooldownUntilMs = 0;
        }

        synchronized boolean runtimeSuccess(long latencyMs) {
            runtimeSuccess();
            long latency = Math.max(0, latencyMs);
            boolean hardSlow = latency >= HARD_SLOW_RESPONSE_MS;
            boolean relativelySlow = latency >= SLOW_RESPONSE_MS
                    && (runtimeLatencyScore == Long.MAX_VALUE
                    || latency * 100L >= runtimeLatencyScore * SLOW_MULTIPLIER_PERCENT);
            if (hardSlow || relativelySlow) {
                consecutiveSlowResponses++;
            } else {
                consecutiveSlowResponses = 0;
                runtimeLatencyScore = runtimeLatencyScore == Long.MAX_VALUE
                        ? latency
                        : (runtimeLatencyScore * 7L + latency * 3L) / 10L;
            }
            boolean shouldProbe = hardSlow || consecutiveSlowResponses >= 2;
            if (shouldProbe) consecutiveSlowResponses = 0;
            return shouldProbe;
        }

        synchronized void failure() {
            consecutiveFailures++;
            consecutiveSlowResponses = 0;
            int shift = Math.min(4, consecutiveFailures - 1);
            long cooldown = Math.min(MAX_FAILURE_COOLDOWN_MS, BASE_FAILURE_COOLDOWN_MS << shift);
            cooldownUntilMs = System.currentTimeMillis() + cooldown;
        }

        synchronized boolean available(long now) {
            return now >= cooldownUntilMs;
        }

        synchronized long latencyScore() {
            return latencyScore;
        }
    }

    /** 不重写 toString 输出密钥，避免误把凭据带入日志或测试失败信息。 */
    public static final class Candidate {
        private final String id;
        private final String endpoint;
        private final String model;
        private final LlmProtocol.Type protocol;
        private final String apiKeyEnv;
        private final String apiKey;
        private final boolean thinkingDisabled;
        private final int tier;

        private Candidate(String id, String endpoint, String model, LlmProtocol.Type protocol,
                          String apiKeyEnv, String apiKey, boolean thinkingDisabled, int tier) {
            this.id = id;
            this.endpoint = endpoint;
            this.model = model;
            this.protocol = protocol;
            this.apiKeyEnv = apiKeyEnv;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.thinkingDisabled = thinkingDisabled;
            if (tier < 1) throw new IllegalArgumentException("tier must be >= 1");
            this.tier = tier;
        }

        public static Candidate fromBaseUrl(String id, String baseUrl, String model,
                                            String protocol, String apiKeyEnv, String apiKey) {
            return fromBaseUrl(id, baseUrl, model, protocol, apiKeyEnv, apiKey, "");
        }

        public static Candidate fromBaseUrl(String id, String baseUrl, String model,
                                            String protocol, String apiKeyEnv, String apiKey,
                                            String requestOption) {
            return fromBaseUrl(id, baseUrl, model, protocol, apiKeyEnv, apiKey, requestOption, 1);
        }

        public static Candidate fromBaseUrl(String id, String baseUrl, String model,
                                            String protocol, String apiKeyEnv, String apiKey,
                                            String requestOption, int tier) {
            boolean thinkingDisabled = "thinking=disabled".equalsIgnoreCase(
                    requestOption == null ? "" : requestOption.trim());
            return fromBaseUrl(id, baseUrl, model, protocol, apiKeyEnv, apiKey,
                    thinkingDisabled, tier);
        }

        private static Candidate fromBaseUrl(String id, String baseUrl, String model,
                                             String protocol, String apiKeyEnv, String apiKey,
                                             boolean thinkingDisabled, int tier) {
            LlmProtocol.Type type = LlmProtocol.Type.parse(protocol);
            return new Candidate(id.trim(), LlmProtocol.endpoint(baseUrl, type), model.trim(),
                    type, apiKeyEnv == null ? "" : apiKeyEnv.trim(), apiKey,
                    thinkingDisabled, tier);
        }

        private boolean sameRoute(Candidate other) {
            return other != null
                    && id.equals(other.id)
                    && endpoint.equals(other.endpoint)
                    && model.equals(other.model)
                    && protocol == other.protocol
                    && apiKeyEnv.equals(other.apiKeyEnv)
                    && apiKey.equals(other.apiKey)
                    && thinkingDisabled == other.thinkingDisabled
                    && tier == other.tier;
        }

        public String id() { return id; }
        public String endpoint() { return endpoint; }
        public String model() { return model; }
        public LlmProtocol.Type protocol() { return protocol; }
        public String apiKeyEnv() { return apiKeyEnv; }
        public String apiKey() { return apiKey; }
        public boolean thinkingDisabled() { return thinkingDisabled; }
        public int tier() { return tier; }

        @Override
        public String toString() {
            return id + "[" + model + ",tier=" + tier + "]";
        }
    }
}
