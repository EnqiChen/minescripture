package dev.minescripture.trigger;

import dev.minescripture.config.EventSpecs;
import dev.minescripture.config.MineScriptureConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: dying in lava fired low_health_survival a fraction of a second
 * before the death, and the near-death verse then muted the death itself. The
 * player got "the Lord will guard you" delivered over their corpse.
 */
class DeathSupersedesTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    private static TriggerPolicy policy() {
        EventSpecs specs = EventSpecs.load(new InputStreamReader(
                DeathSupersedesTest.class.getResourceAsStream("/events.json"), StandardCharsets.UTF_8));
        MineScriptureConfig cfg = MineScriptureConfig.builder().build();
        return new TriggerPolicy(specs, cfg,
                new AiBudgetGuard(cfg.budgetPlayerPerHour, cfg.budgetServerPerHour));
    }

    @Test
    void aDeathIsNeverMutedByTheNearDeathThatPrecededIt() {
        TriggerPolicy p = policy();
        assertTrue(p.evaluate(TriggerContext.of(P1, "low_health_survival", 0), false, false).present());
        // One second later the lava finishes the job.
        TriggerPolicy.Decision death =
                p.evaluate(TriggerContext.of(P1, "player_death", 1_000L), false, false);
        assertTrue(death.present(), "the death must still speak");
        assertTrue(death.useAi(), "and it deserves its own reading");
    }

    @Test
    void deathIsStillPacedSoItCannotBecomeSpam() {
        TriggerPolicy p = policy();
        assertTrue(p.evaluate(TriggerContext.of(P1, "player_death", 0), false, false).present());
        assertEquals("death_pacing",
                p.evaluate(TriggerContext.of(P1, "player_death", 5_000L), false, false).reason(),
                "exempt from the global floor, but not from its own pacing");
    }

    @Test
    void ordinaryMomentsStillRespectTheGlobalFloor() {
        TriggerPolicy p = policy();
        assertTrue(p.evaluate(TriggerContext.of(P1, "eating_bread", 0), false, false).present());
        assertEquals("global_cooldown",
                p.evaluate(TriggerContext.of(P1, "taming", 5_000L), false, false).reason());
    }
}
