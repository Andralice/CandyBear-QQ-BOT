package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainModelPoolTest {

    @Test
    void parsesRoutesAndResolvesOnlyEnvironmentNamedCredentials() {
        List<MainModelPool.Candidate> candidates = MainModelPool.parseCandidates(
                "gemini-lite|https://yetoken.vip/v1|gemini-3.1-flash-lite|anthropic|YETOKEN_API_KEY"
                        + ";luna-1|https://www.wyy22.com/v1|gpt-5.6-luna|openai|WYY22_API_KEY_1"
                        + ";luna-2|https://www.wyy22.com/v1|gpt-5.6-luna|openai|WYY22_API_KEY_2"
                        + ";claude-opus|https://yetoken.vip/v1|claude-opus-4-6|anthropic|YETOKEN_CLAUDE_API_KEY"
                        + ";deepseek-flash|https://api.deepseek.com/v1|deepseek-flash|openai|DEEPSEEK_FLASH_API_KEY|thinking=disabled"
                        + ";literal|https://example.invalid/v1|model|openai|literal-key",
                Map.of(
                        "YETOKEN_API_KEY", "unit-test-secret",
                        "WYY22_API_KEY_1", "unit-test-wyy-1",
                        "WYY22_API_KEY_2", "unit-test-wyy-2",
                        "YETOKEN_CLAUDE_API_KEY", "unit-test-claude",
                        "DEEPSEEK_FLASH_API_KEY", "unit-test-deepseek")::get);

        assertEquals(5, candidates.size());
        MainModelPool.Candidate candidate = candidates.get(0);
        assertEquals("gemini-lite", candidate.id());
        assertEquals("https://yetoken.vip/v1/messages", candidate.endpoint());
        assertEquals(LlmProtocol.Type.ANTHROPIC, candidate.protocol());
        assertEquals("unit-test-secret", candidate.apiKey());
        assertFalse(candidate.toString().contains("unit-test-secret"));

        assertEquals("https://www.wyy22.com/v1/chat/completions", candidates.get(1).endpoint());
        assertEquals(LlmProtocol.Type.OPENAI, candidates.get(1).protocol());
        assertEquals("unit-test-wyy-1", candidates.get(1).apiKey());
        assertEquals("unit-test-wyy-2", candidates.get(2).apiKey());
        assertEquals("claude-opus", candidates.get(3).id());
        assertEquals("claude-opus-4-6", candidates.get(3).model());
        assertEquals("unit-test-claude", candidates.get(3).apiKey());
        assertEquals("deepseek-flash", candidates.get(4).model());
        assertTrue(candidates.get(4).thinkingDisabled());
    }

    @Test
    void parsesOptionalPriorityTierAndKeepsLegacyRoutesAtTierOne() {
        List<MainModelPool.Candidate> candidates = MainModelPool.parseCandidates(
                "tier-two|https://two.example/v1|tier-two-model|openai|KEY|tier=2|thinking=disabled"
                        + ";legacy|https://legacy.example/v1|legacy-model|openai|KEY",
                Map.of("KEY", "unit-test-key")::get);

        assertEquals(2, candidates.size());
        assertEquals(2, candidates.get(0).tier());
        assertTrue(candidates.get(0).thinkingDisabled());
        assertEquals(1, candidates.get(1).tier());
    }

    @Test
    void manuallySelectsAHealthyRouteAndPinsIt() {
        MainModelPool.Candidate first = candidate("first");
        MainModelPool.Candidate second = candidate("second");
        MainModelPool pool = new MainModelPool(List.of(first, second), false, 10_000, 1_000);
        AtomicInteger probeCalls = new AtomicInteger();
        try {
            pool.start((candidate, timeoutMs) -> {
                probeCalls.incrementAndGet();
                assertSame(second, candidate);
                return MainModelPool.ProbeResult.healthy(candidate, 350);
            });

            MainModelPool.ManualSelectionResult result = pool.selectManually("second", false);

            assertEquals(MainModelPool.ManualSelectionStatus.SELECTED, result.status());
            assertEquals(350, result.latencyMs());
            assertSame(second, pool.snapshot());
            assertTrue(pool.isManuallySelected());
            assertEquals(1, probeCalls.get());
        } finally {
            pool.close();
        }
    }

    @Test
    void requiresExplicitConfirmationBeforeSelectingASlowRoute() {
        MainModelPool.Candidate first = candidate("first");
        MainModelPool.Candidate second = candidate("second");
        MainModelPool pool = new MainModelPool(List.of(first, second), false, 10_000, 1_000);
        AtomicInteger probeCalls = new AtomicInteger();
        try {
            pool.start((candidate, timeoutMs) -> {
                probeCalls.incrementAndGet();
                return MainModelPool.ProbeResult.healthy(candidate, 4_001);
            });

            MainModelPool.ManualSelectionResult firstAttempt = pool.selectManually("second", false);
            assertEquals(MainModelPool.ManualSelectionStatus.SLOW_CONFIRMATION_REQUIRED, firstAttempt.status());
            assertSame(first, pool.snapshot());
            assertFalse(pool.isManuallySelected());

            MainModelPool.ManualSelectionResult confirmed = pool.selectManually("second", true);
            assertEquals(MainModelPool.ManualSelectionStatus.SELECTED, confirmed.status());
            assertSame(second, pool.snapshot());
            assertTrue(pool.isManuallySelected());
            assertEquals(2, probeCalls.get());
        } finally {
            pool.close();
        }
    }

    @Test
    void refusesAnUnavailableRouteWithoutChangingTheActiveRoute() {
        MainModelPool.Candidate first = candidate("first");
        MainModelPool.Candidate second = candidate("second");
        MainModelPool pool = new MainModelPool(List.of(first, second), false, 10_000, 1_000);
        AtomicInteger probeCalls = new AtomicInteger();
        try {
            pool.start((candidate, timeoutMs) -> {
                probeCalls.incrementAndGet();
                assertSame(second, candidate);
                return MainModelPool.ProbeResult.failed(candidate, 80, "HTTP 503");
            });

            MainModelPool.ManualSelectionResult result = pool.selectManually("second", false);

            assertEquals(MainModelPool.ManualSelectionStatus.UNAVAILABLE, result.status());
            assertSame(first, pool.snapshot());
            assertFalse(pool.isManuallySelected());
            assertEquals(1, probeCalls.get());
        } finally {
            pool.close();
        }
    }

    @Test
    void healthyAutomaticProbesKeepTheManualRouteAndRealFailureReleasesIt() {
        MainModelPool.Candidate first = candidate("first");
        MainModelPool.Candidate second = candidate("second");
        MainModelPool pool = new MainModelPool(List.of(first, second), false, 10_000, 1_000);
        try {
            pool.start((candidate, timeoutMs) -> MainModelPool.ProbeResult.healthy(candidate, 100));
            assertEquals(MainModelPool.ManualSelectionStatus.SELECTED,
                    pool.selectManually("second", false).status());

            assertSame(second, pool.selectFastest(List.of(
                    MainModelPool.ProbeResult.healthy(first, 20),
                    MainModelPool.ProbeResult.healthy(second, 100))));
            assertTrue(pool.isManuallySelected());
            assertTrue(pool.reportSuccess(second, 15_000));
            assertSame(second, pool.snapshot());
            assertTrue(pool.isManuallySelected());

            assertSame(first, pool.reportFailure(second, "HTTP 500"));
            assertFalse(pool.isManuallySelected());
        } finally {
            pool.close();
        }
    }

    private static MainModelPool.Candidate candidate(String id) {
        return MainModelPool.Candidate.fromBaseUrl(
                id, "https://" + id + ".example/v1", id + "-model", "openai", "KEY", "key");
    }
    @Test
    void selectsFastestHealthyRouteAndKeepsCurrentWhenAllFail() {
        MainModelPool.Candidate first = MainModelPool.Candidate.fromBaseUrl(
                "first", "https://one.example/v1", "first-model", "openai", "KEY", "key");
        MainModelPool.Candidate second = MainModelPool.Candidate.fromBaseUrl(
                "second", "https://two.example/v1", "second-model", "anthropic", "KEY", "key");
        MainModelPool.Candidate third = MainModelPool.Candidate.fromBaseUrl(
                "third", "https://three.example/v1", "third-model", "anthropic", "KEY", "key");
        MainModelPool pool = new MainModelPool(List.of(first, second, third), true, 10_000, 1_000);
        try {
            assertSame(first, pool.snapshot());
            assertSame(second, pool.selectFastest(List.of(
                    MainModelPool.ProbeResult.healthy(first, 80, 12, 3),
                    MainModelPool.ProbeResult.healthy(second, 20, 14, 4),
                    MainModelPool.ProbeResult.healthy(third, 40, 16, 5))));
            assertEquals(new MainModelPool.ProbeUsage(3, 42, 12), pool.probeUsage());
            assertSame(second, pool.selectFastest(List.of(
                    MainModelPool.ProbeResult.failed(first, 10, "HTTP 500"),
                    MainModelPool.ProbeResult.failed(second, 10, "timeout"),
                    MainModelPool.ProbeResult.failed(third, 10, "HTTP 429"))));
        } finally {
            pool.close();
        }
    }

    @Test
    void prefersTierOneOverFasterTierTwoAndFallsBackWhenTierOneFails() {
        MainModelPool.Candidate tierOne = MainModelPool.Candidate.fromBaseUrl(
                "tier-one", "https://one.example/v1", "tier-one-model", "openai", "KEY", "key", "", 1);
        MainModelPool.Candidate tierTwo = MainModelPool.Candidate.fromBaseUrl(
                "tier-two", "https://two.example/v1", "tier-two-model", "openai", "KEY", "key", "", 2);
        MainModelPool pool = new MainModelPool(List.of(tierOne, tierTwo), true, 10_000, 1_000);
        try {
            assertSame(tierOne, pool.selectFastest(List.of(
                    MainModelPool.ProbeResult.healthy(tierOne, 100),
                    MainModelPool.ProbeResult.healthy(tierTwo, 10))));
            assertSame(tierTwo, pool.selectFastest(List.of(
                    MainModelPool.ProbeResult.failed(tierOne, 10, "HTTP 500"),
                    MainModelPool.ProbeResult.healthy(tierTwo, 10))));
        } finally {
            pool.close();
        }
    }

    @Test
    void attemptsAvailableTierOneBeforeActiveTierTwo() {
        MainModelPool.Candidate tierTwo = MainModelPool.Candidate.fromBaseUrl(
                "tier-two", "https://two.example/v1", "tier-two-model", "openai", "KEY", "key", "", 2);
        MainModelPool.Candidate tierOne = MainModelPool.Candidate.fromBaseUrl(
                "tier-one", "https://one.example/v1", "tier-one-model", "openai", "KEY", "key", "", 1);
        MainModelPool pool = new MainModelPool(List.of(tierTwo, tierOne), true, 10_000, 1_000);
        try {
            assertSame(tierOne, pool.attemptOrder().get(0));
            pool.reportFailure(tierOne, "HTTP 500");
            assertSame(tierTwo, pool.attemptOrder().get(0));
        } finally {
            pool.close();
        }
    }

    @Test
    void immediatelyFailsOverAndHotReloadsWithoutMutatingInflightCandidate() {
        MainModelPool.Candidate first = MainModelPool.Candidate.fromBaseUrl(
                "first", "https://one.example/v1", "first-model", "openai", "KEY", "key");
        MainModelPool.Candidate second = MainModelPool.Candidate.fromBaseUrl(
                "second", "https://two.example/v1", "second-model", "anthropic", "KEY", "key");
        MainModelPool pool = new MainModelPool(List.of(first, second), true, 10_000, 1_000);
        try {
            MainModelPool.Candidate inflight = pool.snapshot();
            assertSame(second, pool.reportFailure(first, "HTTP 500"));
            assertSame(first, inflight);
            assertSame(second, pool.attemptOrder().get(0));

            assertTrue(pool.reloadCandidates(
                    "second|https://new.example/v1|second-v2|openai|NEW_KEY"
                            + ";third|https://three.example/v1|third-model|anthropic|NEW_KEY",
                    ignored -> "new-key"));
            assertEquals(2, pool.candidates().size());
            assertEquals("second", pool.snapshot().id());
            assertEquals("second-v2", pool.snapshot().model());
            assertEquals("https://new.example/v1/chat/completions", pool.snapshot().endpoint());
            assertNotSame(second, pool.snapshot());
            MainModelPool.Candidate reloaded = pool.snapshot();
            assertSame(reloaded, pool.reportFailure(second, "stale in-flight route"));
        } finally {
            pool.close();
        }
    }

    @Test
    void detectsConsecutiveSlowResponsesAndImmediatelyMovesAfterHardSlowResponse() {
        MainModelPool.Candidate first = MainModelPool.Candidate.fromBaseUrl(
                "first", "https://one.example/v1", "first-model", "openai", "KEY", "key");
        MainModelPool.Candidate second = MainModelPool.Candidate.fromBaseUrl(
                "second", "https://two.example/v1", "second-model", "openai", "KEY", "key");
        MainModelPool pool = new MainModelPool(List.of(first, second), true, 10_000, 1_000);
        try {
            assertFalse(pool.reportSuccess(first, 4_000));
            assertFalse(pool.reportSuccess(first, 9_000));
            assertTrue(pool.reportSuccess(first, 9_000));
            assertSame(first, pool.snapshot());

            assertTrue(pool.reportSuccess(first, 15_000));
            assertSame(second, pool.snapshot());
        } finally {
            pool.close();
        }
    }

    @Test
    void startsWithOneProbeRoundAndOnlyProbesAgainAfterSlowdown() throws Exception {
        MainModelPool.Candidate first = MainModelPool.Candidate.fromBaseUrl(
                "first", "https://one.example/v1", "first-model", "openai", "KEY", "key");
        MainModelPool.Candidate second = MainModelPool.Candidate.fromBaseUrl(
                "second", "https://two.example/v1", "second-model", "openai", "KEY", "key");
        MainModelPool pool = new MainModelPool(List.of(first, second), true, 1_000, 1_000);
        AtomicInteger probes = new AtomicInteger();
        CountDownLatch startupRound = new CountDownLatch(2);
        CountDownLatch twoRounds = new CountDownLatch(4);
        try {
            pool.start((candidate, timeoutMs) -> {
                probes.incrementAndGet();
                startupRound.countDown();
                twoRounds.countDown();
                return MainModelPool.ProbeResult.healthy(candidate, 100);
            });
            assertTrue(startupRound.await(2, TimeUnit.SECONDS));
            TimeUnit.MILLISECONDS.sleep(1_050);
            assertEquals(2, probes.get());

            assertFalse(pool.reportSuccess(first, 4_000));
            assertFalse(pool.reportSuccess(first, 9_000));
            assertTrue(pool.reportSuccess(first, 9_000));
            assertTrue(twoRounds.await(2, TimeUnit.SECONDS));
            assertEquals(4, probes.get());
        } finally {
            pool.close();
        }
    }

    @Test
    void requiresMateriallyFasterRouteBeforeSwitching() {
        MainModelPool.Candidate first = MainModelPool.Candidate.fromBaseUrl(
                "first", "https://one.example/v1", "first-model", "openai", "KEY", "key");
        MainModelPool.Candidate second = MainModelPool.Candidate.fromBaseUrl(
                "second", "https://two.example/v1", "second-model", "openai", "KEY", "key");
        MainModelPool pool = new MainModelPool(List.of(first, second), true, 10_000, 1_000);
        try {
            assertSame(first, pool.selectFastest(List.of(
                    MainModelPool.ProbeResult.healthy(first, 100),
                    MainModelPool.ProbeResult.healthy(second, 91))));
        } finally {
            pool.close();
        }

        MainModelPool exactPool = new MainModelPool(List.of(first, second), true, 10_000, 1_000);
        try {
            assertSame(second, exactPool.selectFastest(List.of(
                    MainModelPool.ProbeResult.healthy(first, 100),
                    MainModelPool.ProbeResult.healthy(second, 90))));
        } finally {
            exactPool.close();
        }
    }

    @Test
    void keepsFasterFallbackWhenFirstConfiguredRouteRecovers() {
        MainModelPool.Candidate first = MainModelPool.Candidate.fromBaseUrl(
                "first", "https://one.example/v1", "first-model", "openai", "KEY", "key");
        MainModelPool.Candidate second = MainModelPool.Candidate.fromBaseUrl(
                "second", "https://two.example/v1", "second-model", "openai", "KEY", "key");
        MainModelPool pool = new MainModelPool(List.of(first, second), true, 10_000, 1_000);
        try {
            assertSame(second, pool.reportFailure(first, "HTTP 500"));
            assertSame(second, pool.selectFastest(List.of(
                    MainModelPool.ProbeResult.healthy(first, 100),
                    MainModelPool.ProbeResult.healthy(second, 20))));
        } finally {
            pool.close();
        }
    }

    @Test
    void fallsBackToLegacyRouteWhenPoolIsDisabledOrCredentialsAreMissing() {
        MainModelPool.Candidate legacy = MainModelPool.Candidate.fromBaseUrl(
                "legacy", "https://legacy.example/v1", "legacy-model", "openai", "BAILIAN_API_KEY", "key");
        MainModelPool pool = MainModelPool.fromConfig(
                legacy, true,
                "gemini|https://yetoken.vip/v1|gemini-3-flash|anthropic|MISSING_KEY",
                10_000, 1_000, ignored -> "");
        try {
            assertFalse(pool.isEnabled());
            assertSame(legacy, pool.snapshot());
            assertTrue(pool.reloadCandidates(
                    "one|https://one.example/v1|one|openai|KEY"
                            + ";two|https://two.example/v1|two|anthropic|KEY",
                    ignored -> "key"));
            assertTrue(pool.isEnabled());
        } finally {
            pool.close();
        }
    }
}
