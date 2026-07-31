package dev.minescripture.trigger;

import dev.minescripture.config.EventSpec;
import dev.minescripture.config.EventSpecs;
import dev.minescripture.config.FallbackPool;
import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.select.Interpretation;
import dev.minescripture.select.MomentProfileCache;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Orchestrates: listener event → policy gate → two-path timing → sink.
 * Bukkit-free: the Gloo interpreter and the Presenter plug in through the
 * {@link InterpretationSource} and {@link MomentSink} interfaces; the plugin
 * supplies Bukkit's main-thread scheduler as {@code mainExecutor}.
 *
 * Two-path timing:
 *  - PREDICTABLE: present instantly from cache (or curated default), then
 *    restock the cache asynchronously with the updated story.
 *  - SUDDEN: fresh interpretation including the just-fired event, bounded by
 *    the sudden timeout; on timeout/failure the curated default presents.
 *    A pre-event cached interpretation is never shown for a sudden moment.
 */
public final class TriggerService {

    public enum Origin { GLOO, CACHE, DEFAULT }

    public interface InterpretationSource {
        CompletableFuture<Interpretation> interpret(TriggerContext ctx, StoryMemory story);
    }

    public interface MomentSink {
        /**
         * @param deathDepth deaths already spoken to in this run; the Presenter
         *                   softens repeats rather than repeating the ceremony.
         */
        void present(TriggerContext ctx, Interpretation interpretation, Origin origin, int deathDepth);
    }

    /** Reconnect grace: a returning player within this window keeps their story. */
    public static final long RECONNECT_GRACE_MS = 120_000L;

    private final MineScriptureConfig config;
    private final EventSpecs specs;
    private final TriggerPolicy policy;
    private final FallbackPool fallback;
    private final MomentProfileCache profileCache;
    private final PlayerStateManager playerState;
    private final InterpretationSource source;
    private final MomentSink sink;
    private final Executor mainExecutor;
    private final java.util.function.Consumer<String> diagnostics;

    private final Map<UUID, StoryMemory> stories = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> firedByEvent = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> suppressedByReason = new ConcurrentHashMap<>();
    private final Map<Origin, AtomicInteger> originCounts = new ConcurrentHashMap<>();

    public TriggerService(MineScriptureConfig config,
                          EventSpecs specs,
                          TriggerPolicy policy,
                          FallbackPool fallback,
                          MomentProfileCache profileCache,
                          PlayerStateManager playerState,
                          InterpretationSource source,
                          MomentSink sink,
                          Executor mainExecutor) {
        this(config, specs, policy, fallback, profileCache, playerState, source, sink, mainExecutor, msg -> {
        });
    }

    public TriggerService(MineScriptureConfig config,
                          EventSpecs specs,
                          TriggerPolicy policy,
                          FallbackPool fallback,
                          MomentProfileCache profileCache,
                          PlayerStateManager playerState,
                          InterpretationSource source,
                          MomentSink sink,
                          Executor mainExecutor,
                          java.util.function.Consumer<String> diagnostics) {
        this.config = config;
        this.specs = specs;
        this.policy = policy;
        this.fallback = fallback;
        this.profileCache = profileCache;
        this.playerState = playerState;
        this.source = source;
        this.sink = sink;
        this.mainExecutor = mainExecutor;
        this.diagnostics = diagnostics;
    }

    /** Re-arms once-ever moments for rehearsal and filming. */
    public void resetMilestones(UUID playerId) {
        playerState.resetMilestones(playerId);
    }

    public StoryMemory story(UUID playerId) {
        return stories.computeIfAbsent(playerId, k -> new StoryMemory(System.currentTimeMillis()));
    }

    public void onJoin(UUID playerId, long now) {
        StoryMemory existing = stories.get(playerId);
        if (existing == null || existing.expired(now, RECONNECT_GRACE_MS)) {
            stories.put(playerId, new StoryMemory(now));
            profileCache.clear(playerId);
        } else {
            existing.clearQuit();
        }
    }

    public void onQuit(UUID playerId, long now) {
        StoryMemory story = stories.get(playerId);
        if (story != null) {
            story.markQuit(now);
        }
        policy.clearSession(playerId);
    }

    /**
     * Entry point for every candidate moment. Safe to call from the main thread.
     *
     * @return the gate's decision, so admin commands can report what actually
     *         happened instead of claiming success for a suppressed moment.
     */
    public TriggerPolicy.Decision submit(TriggerContext ctx) {
        EventSpec spec = specs.get(ctx.eventKey());
        if (spec == null) {
            count(suppressedByReason, "unknown_event");
            return TriggerPolicy.Decision.suppress("unknown_event");
        }
        StoryMemory story = story(ctx.playerId());
        boolean muted = playerState.isMuted(ctx.playerId());
        boolean milestoneDone = playerState.milestoneDone(ctx.playerId(), ctx.eventKey());

        TriggerPolicy.Decision decision = policy.evaluate(ctx, muted, milestoneDone);

        if (!decision.present()) {
            // Suppressed deaths still shape the story arc; other repeats collapse
            // into aggregates — the AFK-grinder guard's memory half.
            if ("player_death".equals(ctx.eventKey())) {
                story.recordEvent(ctx.eventKey(), ctx.cause(), ctx.at());
            } else {
                story.recordCollapsed(ctx.eventKey());
            }
            count(suppressedByReason, decision.reason());
            return decision;
        }

        if (spec.once() == EventSpec.Once.PERSISTENT) {
            playerState.markMilestone(ctx.playerId(), ctx.eventKey());
        }
        story.recordEvent(ctx.eventKey(), ctx.cause(), ctx.at());
        long postVersion = story.storyVersion();
        count(firedByEvent, ctx.eventKey());

        if (spec.path() == EventSpec.Path.SUDDEN) {
            sudden(ctx, story, decision, postVersion);
        } else {
            predictable(ctx, story, decision, postVersion);
        }
        return decision;
    }

    /**
     * Filming path: runs the real pipeline — real story, real Gloo call, real
     * verification — but skips pacing so a beat can be re-shot immediately, and
     * does not burn once-ever milestones. Nothing about the interpretation is
     * faked; only the gate is stood down.
     */
    public void submitForFilming(TriggerContext ctx) {
        EventSpec spec = specs.get(ctx.eventKey());
        if (spec == null) {
            diagnostics.accept("unknown event: " + ctx.eventKey());
            return;
        }
        TriggerContext demo = ctx.isDemo() ? ctx : ctx.asDemo();
        StoryMemory story = story(demo.playerId());
        story.recordEvent(demo.eventKey(), demo.cause(), demo.at());
        long postVersion = story.storyVersion();
        count(firedByEvent, demo.eventKey());
        TriggerPolicy.Decision forced = new TriggerPolicy.Decision(true, true, "filming", 0);
        if (spec.path() == EventSpec.Path.SUDDEN) {
            sudden(demo, story, forced, postVersion);
        } else {
            predictable(demo, story, forced, postVersion);
        }
    }

    private void sudden(TriggerContext ctx, StoryMemory story, TriggerPolicy.Decision decision, long postVersion) {
        if (decision.useAi() && source != null) {
            long startedAt = System.currentTimeMillis();
            source.interpret(ctx, story)
                    .orTimeout(config.timeoutSuddenMs, TimeUnit.MILLISECONDS)
                    .whenComplete((interp, err) -> {
                        long tookMs = System.currentTimeMillis() - startedAt;
                        if (err != null || interp == null) {
                            // Never let this be silent: a fallback that looks like
                            // success is how "the AI isn't working" hides for days.
                            diagnostics.accept("[" + ctx.eventKey() + "] fell back to a curated verse after "
                                    + tookMs + "ms (cap " + config.timeoutSuddenMs + "ms): "
                                    + (err == null ? "no interpretation" : err.getClass().getSimpleName()));
                            deliver(ctx, defaultFor(ctx), Origin.DEFAULT, decision.deathDepth());
                        } else {
                            diagnostics.accept("[" + ctx.eventKey() + "] interpreted in " + tookMs
                                    + "ms → " + interp.emphasis() + " / " + interp.tone());
                            profileCache.put(ctx.playerId(), ctx.eventKey(), interp, postVersion, ctx.at());
                            deliver(ctx, interp, Origin.GLOO, decision.deathDepth());
                        }
                    });
        } else {
            // No AI granted (over budget): a sudden moment must never show a
            // stale pre-event profile — the curated default is the honest pick.
            deliver(ctx, defaultFor(ctx), Origin.DEFAULT, decision.deathDepth());
        }
    }

    private void predictable(TriggerContext ctx, StoryMemory story, TriggerPolicy.Decision decision, long postVersion) {
        MomentProfileCache.Entry cached = profileCache.get(ctx.playerId(), ctx.eventKey());
        if (cached == null) {
            // Nothing cached. Serving a curated verse now and asking Gloo for one
            // "for next time" only pays off if this event fires again — and
            // first_join and first_nightfall fire once per player ever, so next
            // time never comes. Those moments would be curated for their entire
            // life. Wait for a real reading instead, on the same bounded terms as
            // a sudden moment; if it does not arrive, the curated verse still does.
            sudden(ctx, story, decision, postVersion);
            return;
        }
        deliver(ctx, cached.interpretation(), Origin.CACHE, decision.deathDepth());
        if (decision.useAi() && source != null) {
            source.interpret(ctx, story)
                    .orTimeout(config.timeoutBackgroundMs, TimeUnit.MILLISECONDS)
                    .whenComplete((interp, err) -> {
                        if (err == null && interp != null) {
                            profileCache.put(ctx.playerId(), ctx.eventKey(), interp, postVersion, ctx.at());
                        } else {
                            diagnostics.accept("[" + ctx.eventKey() + "] background restock failed: "
                                    + (err == null ? "no interpretation" : err.getClass().getSimpleName()));
                        }
                    });
        }
    }

    private void deliver(TriggerContext ctx, Interpretation interpretation, Origin origin, int deathDepth) {
        originCounts.computeIfAbsent(origin, k -> new AtomicInteger()).incrementAndGet();
        mainExecutor.execute(() -> sink.present(ctx, interpretation, origin, deathDepth));
    }

    private Interpretation defaultFor(TriggerContext ctx) {
        Interpretation def = fallback.defaultInterpretation(ctx.eventKey());
        return def != null ? def
                : new Interpretation("presence", "moment", "hope", "solemn", List.of(), null, "generic default");
    }

    private static void count(Map<String, AtomicInteger> map, String key) {
        map.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    // --- stats for /msc stats ---

    public Map<String, Integer> firedSnapshot() {
        return snapshot(firedByEvent);
    }

    public Map<String, Integer> suppressedSnapshot() {
        return snapshot(suppressedByReason);
    }

    public Map<String, Integer> originSnapshot() {
        return originCounts.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), e -> e.getValue().get()));
    }

    private static Map<String, Integer> snapshot(Map<String, AtomicInteger> map) {
        return map.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
}
