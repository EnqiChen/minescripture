package dev.minescripture.trigger;

import dev.minescripture.config.EventSpec;
import dev.minescripture.config.EventSpecs;
import dev.minescripture.config.MineScriptureConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The gate. Pure Java, Bukkit-free, deterministic: all timing derives from
 * {@link TriggerContext#at()}, so tests drive a fake clock through contexts.
 *
 * Check order (the AFK-grinder fix depends on cooldowns running BEFORE the
 * budget gate): mute → milestone/pair flags → debounce → per-event cooldown →
 * death-spam guard → global cooldown → session cap → [present granted] →
 * unchanged-story check → AI budget.
 */
public final class TriggerPolicy {

    public record Decision(boolean present, boolean useAi, String reason) {
        public static Decision suppress(String reason) {
            return new Decision(false, false, reason);
        }

        public static Decision presentWithAi() {
            return new Decision(true, true, "ai");
        }

        public static Decision presentCached(String reason) {
            return new Decision(true, false, reason);
        }
    }

    private static final long DEATH_SPAM_WINDOW_MS = 5 * 60_000L;
    private static final int DEATH_SPAM_COUNT = 3;
    private static final long DEATH_SPAM_SUPPRESS_MS = 15 * 60_000L;

    private final EventSpecs specs;
    private final MineScriptureConfig config;
    private final AiBudgetGuard budget;

    private final Map<String, Long> lastFire = new HashMap<>();          // scoped key → millis
    private final Map<String, Long> lastAttempt = new HashMap<>();       // debounce, scoped key → millis
    private final Map<UUID, Long> lastPresentation = new HashMap<>();    // global cooldown
    private final Map<UUID, Integer> sessionCounts = new HashMap<>();
    private final Map<UUID, Deque<Long>> deathTimes = new HashMap<>();
    private final Map<UUID, Long> deathSuppressUntil = new HashMap<>();
    private final Map<UUID, Long> lastInterpretedVersion = new HashMap<>();
    private final Map<UUID, Long> lastLevity = new HashMap<>();
    private final Set<String> firedPairs = new HashSet<>();

    public TriggerPolicy(EventSpecs specs, MineScriptureConfig config, AiBudgetGuard budget) {
        this.specs = specs;
        this.config = config;
        this.budget = budget;
    }

    public synchronized Decision evaluate(TriggerContext ctx, boolean muted, boolean milestoneDone, long storyVersion) {
        EventSpec spec = specs.get(ctx.eventKey());
        if (spec == null) {
            return Decision.suppress("unknown_event");
        }
        long now = ctx.at();
        UUID player = ctx.playerId();

        // Deaths always enter the spam window, even ones suppressed below —
        // a grinder's suppressed deaths are exactly the signal.
        if ("player_death".equals(ctx.eventKey())) {
            Deque<Long> times = deathTimes.computeIfAbsent(player, k -> new ArrayDeque<>());
            times.addLast(now);
            while (!times.isEmpty() && times.peekFirst() < now - DEATH_SPAM_WINDOW_MS) {
                times.removeFirst();
            }
        }

        if (muted) {
            return Decision.suppress("muted");
        }
        if (spec.once() == EventSpec.Once.PERSISTENT && milestoneDone) {
            return Decision.suppress("milestone_done");
        }
        if (spec.once() == EventSpec.Once.SESSION_PAIR && ctx.pairKey() != null
                && firedPairs.contains(ctx.pairKey())) {
            return Decision.suppress("pair_done");
        }

        String scopedKey = scopedKey(spec, ctx);

        if (spec.debounceSeconds() > 0) {
            Long last = lastAttempt.get(scopedKey);
            lastAttempt.put(scopedKey, now); // rolling: every attempt refreshes the window
            if (last != null && now - last < spec.debounceSeconds() * 1000L) {
                return Decision.suppress("debounce");
            }
        }

        if (spec.cooldownSeconds() > 0) {
            Long last = lastFire.get(scopedKey);
            if (last != null && now - last < spec.cooldownSeconds() * 1000L) {
                return Decision.suppress("event_cooldown");
            }
        }

        if ("player_death".equals(ctx.eventKey())) {
            Long until = deathSuppressUntil.get(player);
            if (until != null && now < until) {
                return Decision.suppress("death_spam");
            }
            if (deathTimes.get(player).size() >= DEATH_SPAM_COUNT) {
                deathSuppressUntil.put(player, now + DEATH_SPAM_SUPPRESS_MS);
                return Decision.suppress("death_spam");
            }
        }

        Long lastShown = lastPresentation.get(player);
        if (lastShown != null && now - lastShown < config.globalCooldownSeconds * 1000L) {
            return Decision.suppress("global_cooldown");
        }

        if (sessionCounts.getOrDefault(player, 0) >= config.sessionCap) {
            return Decision.suppress("session_cap");
        }

        // Presentation granted — commit gate state.
        lastFire.put(scopedKey, now);
        lastPresentation.put(player, now);
        sessionCounts.merge(player, 1, Integer::sum);
        if (spec.once() == EventSpec.Once.SESSION_PAIR && ctx.pairKey() != null) {
            firedPairs.add(ctx.pairKey());
        }

        // AI decision: unchanged story → reuse last interpretation; then budget.
        Long lastVersion = lastInterpretedVersion.get(player);
        if (lastVersion != null && lastVersion == storyVersion) {
            return Decision.presentCached("unchanged_story");
        }
        if (!budget.tryAcquire(player, spec.priority(), now)) {
            return Decision.presentCached("budget");
        }
        return Decision.presentWithAi();
    }

    /** Called by TriggerService after it records the moment and commits to an AI call. */
    public synchronized void markInterpreted(UUID player, long storyVersion) {
        lastInterpretedVersion.put(player, storyVersion);
    }

    /** Separate levity cooldown — humor must be rarer than Scripture. */
    public synchronized boolean levityAllowed(UUID player, long now) {
        Long last = lastLevity.get(player);
        return last == null || now - last >= config.levityCooldownSeconds * 1000L;
    }

    public synchronized void markLevity(UUID player, long now) {
        lastLevity.put(player, now);
    }

    /** Session-scoped state resets at logout. Cooldowns survive relogs by design. */
    public synchronized void clearSession(UUID player) {
        sessionCounts.remove(player);
        lastInterpretedVersion.remove(player);
        firedPairs.removeIf(pair -> pair.contains(player.toString()));
    }

    public synchronized int sessionCount(UUID player) {
        return sessionCounts.getOrDefault(player, 0);
    }

    private String scopedKey(EventSpec spec, TriggerContext ctx) {
        return spec.scope() == EventSpec.Scope.WORLD
                ? "W:" + ctx.worldKey() + ":" + ctx.eventKey()
                : "P:" + ctx.playerId() + ":" + ctx.eventKey();
    }
}
