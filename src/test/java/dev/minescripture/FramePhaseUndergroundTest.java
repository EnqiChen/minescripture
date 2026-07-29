package dev.minescripture;

import dev.minescripture.config.FallbackPool;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression: dying in a deep cave at world-time 23960 produced "You awaken at
 * dawn." It was dawn — seventy blocks overhead, where the player could not see
 * it. A frame has to describe what they can actually check.
 */
class FramePhaseUndergroundTest {

    private static FallbackPool pool() {
        return FallbackPool.load(new InputStreamReader(
                FramePhaseUndergroundTest.class.getResourceAsStream("/fallback.json"),
                StandardCharsets.UTF_8));
    }

    @Test
    void undergroundOverridesWhateverTheSkyIsDoing() {
        assertEquals("underground", FallbackPool.phaseOf(23_960, false));
        assertEquals("underground", FallbackPool.phaseOf(6_000, false));
        assertEquals("dawn", FallbackPool.phaseOf(23_960, true));
        assertEquals("day", FallbackPool.phaseOf(6_000, true));
    }

    @Test
    void theExactCaseFromPlayTesting() {
        FallbackPool.EventDefault death = pool().defaultsFor("player_death");
        String inCave = death.frameFor(FallbackPool.phaseOf(23_960, false));
        assertFalse(inCave.toLowerCase().contains("dawn"),
                "died in the diamond chamber; dawn is not visible from there. was: " + inCave);
        assertEquals("You awaken at dawn.", death.frameFor(FallbackPool.phaseOf(23_960, true)));
    }
}
