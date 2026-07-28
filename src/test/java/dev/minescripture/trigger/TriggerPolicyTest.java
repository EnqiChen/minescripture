package dev.minescripture.trigger;

import dev.minescripture.config.EventSpecs;
import dev.minescripture.config.MineScriptureConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bukkit-free gate tests. All timing is driven through TriggerContext.at(),
 * so every scenario is deterministic.
 */
class TriggerPolicyTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static EventSpecs realSpecs() {
        return EventSpecs.load(new InputStreamReader(
                TriggerPolicyTest.class.getResourceAsStream("/events.json"), StandardCharsets.UTF_8));
    }

    private static TriggerContext ctx(String event, long atMillis) {
        return TriggerContext.of(P1, event, atMillis);
    }

    private static TriggerPolicy policy(EventSpecs specs, MineScriptureConfig cfg) {
        return new TriggerPolicy(specs, cfg,
                new AiBudgetGuard(cfg.budgetPlayerPerHour, cfg.budgetServerPerHour));
    }

    // ---- THE AFK-GRINDER SCENARIO (plan verification item) ----
    // A player parked in a mob farm takes low-health hits every 3 seconds for
    // two minutes. Production events.json gives low_health_survival a 600 s
    // cooldown: exactly ONE moment presents, exactly ONE AI budget slot burns.
    @Test
    void afkGrinderCannotDrainTheAiBudget() {
        MineScriptureConfig cfg = MineScriptureConfig.builder().build();
        AiBudgetGuard budget = new AiBudgetGuard(cfg.budgetPlayerPerHour, cfg.budgetServerPerHour);
        TriggerPolicy policy = new TriggerPolicy(realSpecs(), cfg, budget);

        int presented = 0;
        int aiGranted = 0;
        long storyVersion = 0;
        for (int i = 0; i < 40; i++) {
            long at = i * 3_000L; // every 3 s
            TriggerPolicy.Decision d = policy.evaluate(ctx("low_health_survival", at), false, false, storyVersion);
            if (d.present()) {
                presented++;
                storyVersion++; // service records a material event on present
                if (d.useAi()) {
                    aiGranted++;
                }
            } else {
                assertEquals("event_cooldown", d.reason(),
                        "grinder repeats must die at the cooldown, before the budget gate");
            }
        }
        assertEquals(1, presented, "40 grinder hits → exactly one presentation");
        assertEquals(1, aiGranted, "40 grinder hits → exactly one AI call");
        assertEquals(1, budget.playerCallsLastHour(P1, 120_000L),
                "the hourly AI budget must be almost untouched — dragon fight later still gets AI");
    }

    @Test
    void mutedPlayerIsAlwaysSuppressed() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        TriggerPolicy.Decision d = policy.evaluate(ctx("player_death", 0), true, false, 0);
        assertFalse(d.present());
        assertEquals("muted", d.reason());
    }

    @Test
    void persistentMilestoneNeverFiresTwice() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        TriggerPolicy.Decision d = policy.evaluate(ctx("found_diamonds", 0), false, true, 0);
        assertEquals("milestone_done", d.reason());
    }

    @Test
    void diamondVeinDebounceSuppressesRapidRebreaks() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("found_diamonds", 0), false, false, 0).present());
        // Second ore block of the same vein 10 s later, milestone flag not yet
        // consulted (belt and braces): debounce must catch it.
        TriggerPolicy.Decision d = policy.evaluate(ctx("found_diamonds", 10_000L), false, false, 1);
        assertEquals("debounce", d.reason());
    }

    @Test
    void deathCooldownSuppressesQuickSecondDeath() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("player_death", 0), false, false, 0).present());
        TriggerPolicy.Decision d = policy.evaluate(ctx("player_death", 30_000L), false, false, 1);
        assertEquals("event_cooldown", d.reason());
    }

    @Test
    void deathSpamGuardKicksInAtThirdDeathInFiveMinutes() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("player_death", 0), false, false, 0).present());
        assertTrue(policy.evaluate(ctx("player_death", 130_000L), false, false, 1).present());
        // Third death within the 5-min window → 15-min suppression begins.
        assertEquals("death_spam",
                policy.evaluate(ctx("player_death", 260_000L), false, false, 2).reason());
        // Still suppressed inside the window...
        assertEquals("death_spam",
                policy.evaluate(ctx("player_death", 400_000L), false, false, 3).reason());
        // ...and presents again once the suppression expires (260 s + 900 s).
        assertTrue(policy.evaluate(ctx("player_death", 1_200_000L), false, false, 4).present());
    }

    @Test
    void globalCooldownSpansDifferentEvents() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("eating_bread", 0), false, false, 0).present());
        TriggerPolicy.Decision d = policy.evaluate(ctx("taming", 10_000L), false, false, 1);
        assertEquals("global_cooldown", d.reason());
        assertTrue(policy.evaluate(ctx("taming", 95_000L), false, false, 1).present());
    }

    @Test
    void sessionCapStopsPresentations() {
        String specsJson = """
                { "events": {
                  "a": {"path":"predictable","tier":"minor","cooldown_s":0},
                  "b": {"path":"predictable","tier":"minor","cooldown_s":0},
                  "c": {"path":"predictable","tier":"minor","cooldown_s":0}
                }}""";
        EventSpecs specs = EventSpecs.load(new StringReader(specsJson));
        MineScriptureConfig cfg = MineScriptureConfig.builder()
                .globalCooldownSeconds(0).sessionCap(2).build();
        TriggerPolicy policy = policy(specs, cfg);
        assertTrue(policy.evaluate(ctx("a", 0), false, false, 0).present());
        assertTrue(policy.evaluate(ctx("b", 1_000L), false, false, 1).present());
        assertEquals("session_cap", policy.evaluate(ctx("c", 2_000L), false, false, 2).reason());
        // Session clear (logout) resets the cap.
        policy.clearSession(P1);
        assertTrue(policy.evaluate(ctx("c", 3_000L), false, false, 3).present());
    }

    @Test
    void overBudgetOnlyPriorityEventsStillGetAi() {
        MineScriptureConfig cfg = MineScriptureConfig.builder()
                .globalCooldownSeconds(0).budgetPlayerPerHour(2).build();
        TriggerPolicy policy = policy(realSpecs(), cfg);
        assertTrue(policy.evaluate(ctx("eating_bread", 0), false, false, 0).useAi());
        assertTrue(policy.evaluate(ctx("taming", 1_000L), false, false, 1).useAi());
        // Budget exhausted: non-priority event presents from cache/fallback...
        TriggerPolicy.Decision sleep = policy.evaluate(ctx("sleep", 2_000L), false, false, 2);
        assertTrue(sleep.present());
        assertFalse(sleep.useAi());
        assertEquals("budget", sleep.reason());
        // ...but a death (priority) still deserves fresh AI.
        TriggerPolicy.Decision death = policy.evaluate(ctx("player_death", 3_000L), false, false, 3);
        assertTrue(death.useAi());
    }

    @Test
    void unchangedStoryReusesLastInterpretation() {
        MineScriptureConfig cfg = MineScriptureConfig.builder().globalCooldownSeconds(0).build();
        TriggerPolicy policy = policy(realSpecs(), cfg);
        assertTrue(policy.evaluate(ctx("eating_bread", 0), false, false, 5).useAi());
        policy.markInterpreted(P1, 6); // service records the moment → version 6
        // Nothing new happened since; next trigger must not burn an AI call.
        TriggerPolicy.Decision d = policy.evaluate(ctx("taming", 100_000L), false, false, 6);
        assertTrue(d.present());
        assertFalse(d.useAi());
        assertEquals("unchanged_story", d.reason());
    }

    @Test
    void fellowshipFiresOncePerPairPerSession() {
        MineScriptureConfig cfg = MineScriptureConfig.builder().globalCooldownSeconds(0).build();
        TriggerPolicy policy = policy(realSpecs(), cfg);
        TriggerContext first = new TriggerContext(P1, "fellowship", null, null, "a|b", 0, java.util.Map.of());
        TriggerContext again = new TriggerContext(P1, "fellowship", null, null, "a|b", 50_000L, java.util.Map.of());
        assertTrue(policy.evaluate(first, false, false, 0).present());
        assertEquals("pair_done", policy.evaluate(again, false, false, 1).reason());
    }

    @Test
    void levityCooldownIsSeparate() {
        MineScriptureConfig cfg = MineScriptureConfig.builder().levityCooldownSeconds(600).build();
        TriggerPolicy policy = policy(realSpecs(), cfg);
        assertTrue(policy.levityAllowed(P1, 0));
        policy.markLevity(P1, 0);
        assertFalse(policy.levityAllowed(P1, 300_000L));
        assertTrue(policy.levityAllowed(P1, 600_000L));
    }
}
