package dev.minescripture.trigger;

import dev.minescripture.config.EventSpecs;
import dev.minescripture.config.FallbackPool;
import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.select.Interpretation;
import dev.minescripture.select.MomentProfileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level two-path timing tests with a stubbed interpretation source and
 * a recording sink. Bukkit-free: mainExecutor is same-thread.
 */
class TriggerServiceTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    record Presented(TriggerContext ctx, Interpretation interp, TriggerService.Origin origin) {
    }

    static class RecordingSink implements TriggerService.MomentSink {
        final List<Presented> presented = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;

        RecordingSink(int expected) {
            this.latch = new CountDownLatch(expected);
        }

        @Override
        public void present(TriggerContext ctx, Interpretation interp, TriggerService.Origin origin, int deathDepth) {
            presented.add(new Presented(ctx, interp, origin));
            latch.countDown();
        }
    }

    private static EventSpecs specs() {
        return EventSpecs.load(new InputStreamReader(
                TriggerServiceTest.class.getResourceAsStream("/events.json"), StandardCharsets.UTF_8));
    }

    private static FallbackPool pool() {
        return FallbackPool.load(new InputStreamReader(
                TriggerServiceTest.class.getResourceAsStream("/fallback.json"), StandardCharsets.UTF_8));
    }

    private static TriggerService service(MineScriptureConfig cfg, Path dataDir,
                                          TriggerService.InterpretationSource source,
                                          TriggerService.MomentSink sink) {
        EventSpecs specs = specs();
        TriggerPolicy policy = new TriggerPolicy(specs, cfg,
                new AiBudgetGuard(cfg.budgetPlayerPerHour, cfg.budgetServerPerHour));
        return new TriggerService(cfg, specs, policy, pool(), new MomentProfileCache(),
                new PlayerStateManager(dataDir), source, sink, Runnable::run);
    }

    private static Interpretation gloo(String emphasis) {
        return new Interpretation("loss", "setback_to_hope", emphasis, "solemn",
                List.of("LAM.3.22-23"), null, "test");
    }

    // ---- THE AFK-GRINDER SCENARIO, END TO END ----
    // 40 rapid low-health events: one presentation, one AI call, and the other
    // 39 collapse into StoryMemory aggregates instead of AI-worthy moments.
    @Test
    void afkGrinderCollapsesIntoAggregates(@TempDir Path tmp) throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        RecordingSink sink = new RecordingSink(1);
        TriggerService svc = service(MineScriptureConfig.builder().build(), tmp,
                (ctx, story) -> {
                    sourceCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(gloo("refuge"));
                }, sink);

        svc.onJoin(P1, 0);
        for (int i = 0; i < 40; i++) {
            svc.submit(TriggerContext.of(P1, "low_health_survival", i * 3_000L));
        }
        assertTrue(sink.latch.await(2, TimeUnit.SECONDS));

        assertEquals(1, sink.presented.size(), "exactly one moment reaches the player");
        assertEquals(1, sourceCalls.get(), "exactly one Gloo call for 40 grinder hits");
        Map<String, Integer> aggregates = svc.story(P1).aggregates();
        assertEquals(39, aggregates.get("low_health_survival_repeats"),
                "suppressed repeats must collapse into aggregates");
        assertEquals(39, svc.suppressedSnapshot().get("event_cooldown"));
        assertEquals(1, svc.firedSnapshot().get("low_health_survival"));
    }

    @Test
    void suddenPathTimeoutFallsBackToCuratedDefault(@TempDir Path tmp) throws Exception {
        RecordingSink sink = new RecordingSink(1);
        // Source hangs forever: the 60 ms sudden cap must rescue the moment.
        TriggerService svc = service(
                MineScriptureConfig.builder().timeoutSuddenMs(60).build(), tmp,
                (ctx, story) -> new CompletableFuture<>(), sink);

        svc.onJoin(P1, 0);
        svc.submit(TriggerContext.of(P1, "player_death", 1_000L));
        assertTrue(sink.latch.await(2, TimeUnit.SECONDS), "fallback must arrive despite the hang");

        Presented p = sink.presented.get(0);
        assertEquals(TriggerService.Origin.DEFAULT, p.origin());
        assertEquals("comfort", p.interp().emphasis(), "death default is the comfort interpretation");
        assertTrue(p.interp().recommendedRefs().contains("LAM.3.22-23"));
    }

    @Test
    void suddenPathUsesFreshInterpretationWhenGlooAnswersInTime(@TempDir Path tmp) throws Exception {
        RecordingSink sink = new RecordingSink(1);
        TriggerService svc = service(MineScriptureConfig.builder().build(), tmp,
                (ctx, story) -> {
                    // The fresh interpretation must see the just-fired death in the story.
                    assertTrue(story.deaths() >= 1, "sudden path interprets a story that includes the event");
                    return CompletableFuture.completedFuture(gloo("perseverance"));
                }, sink);

        svc.onJoin(P1, 0);
        svc.submit(TriggerContext.of(P1, "player_death", 1_000L));
        assertTrue(sink.latch.await(2, TimeUnit.SECONDS));

        Presented p = sink.presented.get(0);
        assertEquals(TriggerService.Origin.GLOO, p.origin());
        assertEquals("perseverance", p.interp().emphasis());
    }

    @Test
    void aColdCacheWaitsForARealReadingRatherThanServingACuratedOne(@TempDir Path tmp) throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        RecordingSink sink = new RecordingSink(2);
        TriggerService svc = service(MineScriptureConfig.builder().build(), tmp,
                (ctx, story) -> {
                    sourceCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(gloo("gratitude"));
                }, sink);

        svc.onJoin(P1, 0);
        // First bread: cache empty → curated default presents instantly, restock happens async.
        svc.submit(TriggerContext.of(P1, "eating_bread", 1_000L));
        // Second bread, well past the event cooldown: now there IS a cached
        // reading, so it appears instantly while a fresh one is fetched behind it.
        svc.submit(TriggerContext.of(P1, "eating_bread", 2_000_000L));
        assertTrue(sink.latch.await(2, TimeUnit.SECONDS));

        // The first firing must not be a curated verse. Serving one and caching a
        // real reading "for next time" is worthless for a moment that fires once
        // per player ever, and it made most of the demo look like a lookup table.
        assertEquals(TriggerService.Origin.GLOO, sink.presented.get(0).origin(),
                "a cold cache should wait for Gloo, not fall back to the pool");
        assertEquals(TriggerService.Origin.CACHE, sink.presented.get(1).origin());
        assertEquals("gratitude", sink.presented.get(0).interp().emphasis());
        assertEquals(2, sourceCalls.get(),
                "one call for the cold moment, one to restock behind the cached serve");
    }

    /**
     * Night falls and the player goes straight to bed — the most ordinary
     * sequence in Minecraft, and the two moments are inherently seconds apart.
     * With sleep outside the priority set the 90-second floor swallowed it every
     * time, silently, on the one night of a player's game where it is new.
     */
    @Test
    void goingToBedRightAfterNightfallStillSpeaks(@TempDir Path tmp) throws Exception {
        RecordingSink sink = new RecordingSink(2);
        TriggerService svc = service(MineScriptureConfig.builder().build(), tmp,
                (ctx, story) -> CompletableFuture.completedFuture(gloo("rest")), sink);

        svc.onJoin(P1, 0);
        svc.submit(TriggerContext.of(P1, "first_nightfall", 1_000L));
        svc.submit(TriggerContext.of(P1, "sleep", 21_000L));
        assertTrue(sink.latch.await(2, TimeUnit.SECONDS), "the sleep moment must not be swallowed");

        assertEquals(2, sink.presented.size());
        assertEquals("sleep", sink.presented.get(1).ctx().eventKey());
        assertEquals(0, svc.suppressedSnapshot().getOrDefault("global_cooldown", 0));
    }

    @Test
    void reconnectWithinGraceKeepsStory(@TempDir Path tmp) {
        TriggerService svc = service(MineScriptureConfig.builder().build(), tmp,
                (ctx, story) -> CompletableFuture.completedFuture(gloo("x")), new RecordingSink(0));
        svc.onJoin(P1, 0);
        svc.story(P1).recordEvent("player_death", "lava", 1_000L);
        svc.onQuit(P1, 2_000L);
        svc.onJoin(P1, 30_000L); // within 120 s grace
        assertEquals(1, svc.story(P1).deaths(), "story survives a quick reconnect");
        svc.onQuit(P1, 40_000L);
        svc.onJoin(P1, 200_000L); // past grace → ephemeral state cleared
        assertEquals(0, svc.story(P1).deaths(), "story clears after the grace window");
    }
}
