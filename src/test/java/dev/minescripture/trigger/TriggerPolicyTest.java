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
        for (int i = 0; i < 40; i++) {
            long at = i * 3_000L; // every 3 s
            TriggerPolicy.Decision d = policy.evaluate(ctx("low_health_survival", at), false, false);
            if (d.present()) {
                presented++;
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
        TriggerPolicy.Decision d = policy.evaluate(ctx("player_death", 0), true, false);
        assertFalse(d.present());
        assertEquals("muted", d.reason());
    }

    @Test
    void persistentMilestoneNeverFiresTwice() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        TriggerPolicy.Decision d = policy.evaluate(ctx("found_diamonds", 0), false, true);
        assertEquals("milestone_done", d.reason());
    }

    @Test
    void diamondVeinDebounceSuppressesRapidRebreaks() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("found_diamonds", 0), false, false).present());
        // Second ore block of the same vein 10 s later, milestone flag not yet
        // consulted (belt and braces): debounce must catch it.
        TriggerPolicy.Decision d = policy.evaluate(ctx("found_diamonds", 10_000L), false, false);
        assertEquals("debounce", d.reason());
    }

    @Test
    void deathCooldownSuppressesQuickSecondDeath() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("player_death", 0), false, false).present());
        TriggerPolicy.Decision d = policy.evaluate(ctx("player_death", 30_000L), false, false);
        assertEquals("death_pacing", d.reason());
    }

    // A bad run thins out but is NEVER silenced. Base death cooldown is 60 s and
    // each death waits 1.75x longer than the last: 60 s, 105 s, 184 s, ...
    @Test
    void repeatedDeathsEscalateButAlwaysEventuallySpeak() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("player_death", 0), false, false).present(), "1st death");

        assertEquals("death_pacing", policy.evaluate(ctx("player_death", 59_000L), false, false).reason());
        assertTrue(policy.evaluate(ctx("player_death", 61_000L), false, false).present(), "2nd after 60 s");

        // Gap widened to 105 s.
        assertEquals("death_pacing", policy.evaluate(ctx("player_death", 150_000L), false, false).reason());
        assertTrue(policy.evaluate(ctx("player_death", 167_000L), false, false).present(), "3rd after 105 s");

        // Gap widened to ~184 s.
        assertEquals("death_pacing", policy.evaluate(ctx("player_death", 300_000L), false, false).reason());
        assertTrue(policy.evaluate(ctx("player_death", 352_000L), false, false).present(), "4th after 184 s");
    }

    // The whole point of the redesign: dying repeatedly must not produce silence.
    // Nine deaths over a rough ten minutes should still be spoken to several times.
    @Test
    void aRoughRunOfNineDeathsIsNeverLockedOut() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        long[] deaths = {0, 287_000L, 342_000L, 381_000L, 415_000L,
                461_000L, 479_000L, 511_000L, 584_000L};
        int presented = 0;
        for (long at : deaths) {
            if (policy.evaluate(ctx("player_death", at), false, false).present()) {
                presented++;
            }
        }
        assertTrue(presented >= 3, "expected several verses across a rough run, got " + presented);
        assertTrue(presented < deaths.length, "but not one for literally every death");
    }

    @Test
    void deathPacingResetsAfterAQuietStretch() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("player_death", 0), false, false).present());
        assertTrue(policy.evaluate(ctx("player_death", 61_000L), false, false).present());
        // 10 quiet minutes later the run has reset, so the base 60 s applies again.
        long later = 61_000L + 700_000L;
        assertTrue(policy.evaluate(ctx("player_death", later), false, false).present());
        assertEquals(0, policy.evaluate(ctx("player_death", later + 10_000L), false, false).deathDepth(),
                "a reset run starts back at full ceremony");
    }

    @Test
    void repeatDeathsReportDepthSoThePresenterCanSoftenThem() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertEquals(0, policy.evaluate(ctx("player_death", 0), false, false).deathDepth());
        assertEquals(1, policy.evaluate(ctx("player_death", 61_000L), false, false).deathDepth());
        assertEquals(2, policy.evaluate(ctx("player_death", 167_000L), false, false).deathDepth());
    }

    // Dying moments after a discovery is the centerpiece demo; the 90 s global
    // floor must not swallow it, because priority events get a 15 s floor.
    @Test
    void priorityEventsGetAShorterGlobalFloor() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("found_diamonds", 0), false, false).present());
        assertTrue(policy.evaluate(ctx("player_death", 20_000L), false, false).present(),
                "death 20 s after diamonds must still speak");
        // A non-priority event in the same window still waits the full 90 s.
        assertEquals("global_cooldown",
                policy.evaluate(ctx("taming", 40_000L), false, false).reason());
    }

    @Test
    void globalCooldownSpansDifferentEvents() {
        TriggerPolicy policy = policy(realSpecs(), MineScriptureConfig.builder().build());
        assertTrue(policy.evaluate(ctx("eating_bread", 0), false, false).present());
        TriggerPolicy.Decision d = policy.evaluate(ctx("taming", 10_000L), false, false);
        assertEquals("global_cooldown", d.reason());
        assertTrue(policy.evaluate(ctx("taming", 95_000L), false, false).present());
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
        assertTrue(policy.evaluate(ctx("a", 0), false, false).present());
        assertTrue(policy.evaluate(ctx("b", 1_000L), false, false).present());
        assertEquals("session_cap", policy.evaluate(ctx("c", 2_000L), false, false).reason());
        // Session clear (logout) resets the cap.
        policy.clearSession(P1);
        assertTrue(policy.evaluate(ctx("c", 3_000L), false, false).present());
    }

    @Test
    void overBudgetOnlyPriorityEventsStillGetAi() {
        MineScriptureConfig cfg = MineScriptureConfig.builder()
                .globalCooldownSeconds(0).globalPriorityCooldownSeconds(0)
                .budgetPlayerPerHour(2).build();
        TriggerPolicy policy = policy(realSpecs(), cfg);
        assertTrue(policy.evaluate(ctx("eating_bread", 0), false, false).useAi());
        assertTrue(policy.evaluate(ctx("taming", 1_000L), false, false).useAi());
        // Budget exhausted: non-priority event presents from cache/fallback...
        TriggerPolicy.Decision sleep = policy.evaluate(ctx("sleep", 2_000L), false, false);
        assertTrue(sleep.present());
        assertFalse(sleep.useAi());
        assertEquals("budget", sleep.reason());
        // ...but a death (priority) still deserves fresh AI.
        TriggerPolicy.Decision death = policy.evaluate(ctx("player_death", 3_000L), false, false);
        assertTrue(death.useAi());
    }

    // Regression: a moment that follows an earlier interpretation is still new
    // information and must get its own reading. The old unchanged-story check
    // compared the story version before recording the current event against the
    // version after recording the previous one, so those were always equal —
    // which silently denied AI to every second moment, deaths included.
    @Test
    void aMomentAfterAnEarlierInterpretationStillGetsItsOwnReading() {
        MineScriptureConfig cfg = MineScriptureConfig.builder().globalCooldownSeconds(0).build();
        TriggerPolicy policy = policy(realSpecs(), cfg);
        assertTrue(policy.evaluate(ctx("first_join", 0), false, false).useAi());
        TriggerPolicy.Decision death = policy.evaluate(ctx("player_death", 100_000L), false, false);
        assertTrue(death.present());
        assertTrue(death.useAi(), "a death after a welcome must reach the AI");
    }

    @Test
    void fellowshipFiresOncePerPairPerSession() {
        MineScriptureConfig cfg = MineScriptureConfig.builder().globalCooldownSeconds(0).build();
        TriggerPolicy policy = policy(realSpecs(), cfg);
        TriggerContext first = new TriggerContext(P1, "fellowship", null, null, "a|b", 0, java.util.Map.of());
        TriggerContext again = new TriggerContext(P1, "fellowship", null, null, "a|b", 50_000L, java.util.Map.of());
        assertTrue(policy.evaluate(first, false, false).present());
        assertEquals("pair_done", policy.evaluate(again, false, false).reason());
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
