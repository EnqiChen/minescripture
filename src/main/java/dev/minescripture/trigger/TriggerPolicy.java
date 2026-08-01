package dev.minescripture.trigger;

import dev.minescripture.config.EventSpec;
import dev.minescripture.config.EventSpecs;
import dev.minescripture.config.MineScriptureConfig;

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
 * budget gate): mute → milestone/pair flags → debounce → per-event cooldown
 * (death-paced) → global cooldown → session cap → [present granted] → AI budget.
 *
 * Restraint here is about pacing, never silence: repeated deaths wait longer
 * each time, but no run of bad luck can lock a player out entirely.
 */
public final class TriggerPolicy {

    /**
     * @param deathDepth how many deaths were already spoken to in this run before
     *                   this one — the Presenter uses it to soften repeats.
     */
    public record Decision(boolean present, boolean useAi, String reason, int deathDepth) {
        public static Decision suppress(String reason) {
            return new Decision(false, false, reason, 0);
        }
    }

    static final String DEATH = "player_death";

    /** Not "the AI failed" — "the AI was never asked, and that is the design." */
    public static final String CURATED_BY_DESIGN = "curated_by_design";

    private final EventSpecs specs;
    private final MineScriptureConfig config;
    private final AiBudgetGuard budget;

    private final Map<String, Long> lastFire = new HashMap<>();          // scoped key → millis
    private final Map<String, Long> lastAttempt = new HashMap<>();       // debounce, scoped key → millis
    private final Map<UUID, Long> lastPresentation = new HashMap<>();    // global cooldown
    private final Map<UUID, Integer> sessionCounts = new HashMap<>();
    private final Map<UUID, Integer> deathCluster = new HashMap<>();     // deaths presented in the current run
    private final Map<UUID, Long> lastLevity = new HashMap<>();
    private final Set<String> firedPairs = new HashSet<>();

    public TriggerPolicy(EventSpecs specs, MineScriptureConfig config, AiBudgetGuard budget) {
        this.specs = specs;
        this.config = config;
        this.budget = budget;
    }

    public synchronized Decision evaluate(TriggerContext ctx, boolean muted, boolean milestoneDone) {
        EventSpec spec = specs.get(ctx.eventKey());
        if (spec == null) {
            return Decision.suppress("unknown_event");
        }
        long now = ctx.at();
        UUID player = ctx.playerId();

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

        // A run of quick deaths resets to depth 0 once the player has been alive
        // a while. Depth is read here and committed only if we actually present.
        int deathDepth = 0;
        if (DEATH.equals(ctx.eventKey())) {
            Long lastDeath = lastFire.get(scopedKey);
            boolean sameRun = lastDeath != null
                    && now - lastDeath <= config.deathClusterResetSeconds * 1000L;
            deathDepth = sameRun ? deathCluster.getOrDefault(player, 0) : 0;
            if (!sameRun) {
                deathCluster.put(player, 0);
            }
        }

        if (spec.cooldownSeconds() > 0) {
            Long last = lastFire.get(scopedKey);
            long cooldownMs = effectiveCooldownMs(spec, ctx.eventKey(), deathDepth);
            if (last != null && now - last < cooldownMs) {
                return Decision.suppress(DEATH.equals(ctx.eventKey()) ? "death_pacing" : "event_cooldown");
            }
        }

        // Priority moments (death, diamonds, first-times) get a shorter floor so
        // that e.g. dying moments after a diamond find is still allowed to speak.
        // Death is exempt altogether: a near-death verse fires moments before the
        // death that follows it, and letting the smaller moment mute the larger
        // one leaves a verse about deliverance standing over a corpse. Death has
        // its own pacing above, so this cannot become spam.
        if (!DEATH.equals(ctx.eventKey())) {
            long globalMs = (spec.priority() ? config.globalPriorityCooldownSeconds
                    : config.globalCooldownSeconds) * 1000L;
            Long lastShown = lastPresentation.get(player);
            if (lastShown != null && now - lastShown < globalMs) {
                return Decision.suppress("global_cooldown");
            }
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
        if (DEATH.equals(ctx.eventKey())) {
            deathCluster.put(player, deathDepth + 1);
        }

        // Some moments are scripted rather than interpreted. A first arrival has
        // no session story by definition — nothing happened yet — so a model can
        // only guess from an event name, and it guessed a verse about creation,
        // then one urging the player to abstain from sinful desires. The welcome
        // says what it means to say, and its verse is chosen to explain the word
        // it uses. This is the exception; everything with a story still asks.
        if (!spec.aiEligible()) {
            return new Decision(true, false, CURATED_BY_DESIGN, deathDepth);
        }
        if (!budget.tryAcquire(player, spec.priority(), now)) {
            return new Decision(true, false, "budget", deathDepth);
        }
        return new Decision(true, true, "ai", deathDepth);
    }

    /**
     * Death pacing: each death in a quick run waits longer than the last, but the
     * gap is capped and never becomes silence. A player who dies four times in
     * five minutes still hears something — just not every single time.
     */
    private long effectiveCooldownMs(EventSpec spec, String eventKey, int deathDepth) {
        long base = spec.cooldownSeconds() * 1000L;
        if (!DEATH.equals(eventKey) || deathDepth <= 1) {
            return base;
        }
        // deathDepth counts deaths already spoken to, so the gap after the first
        // one is the base wait; escalation starts from the second onward.
        double scaled = base * Math.pow(config.deathEscalationFactor, deathDepth - 1);
        return (long) Math.min(scaled, config.deathMaxCooldownSeconds * 1000L);
    }

    /** How many deaths have already been spoken to in the current run (0 = none). */
    public synchronized int deathClusterDepth(UUID player) {
        return deathCluster.getOrDefault(player, 0);
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
        deathCluster.remove(player);
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
