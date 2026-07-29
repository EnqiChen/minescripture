package dev.minescripture;

import dev.minescripture.config.FallbackPool;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frame must never claim a time of day that isn't true when the player reads
 * it. Found in play-testing: dying at midnight showed "You awaken at dawn."
 */
class FramePhaseTest {

    private static FallbackPool pool() {
        return FallbackPool.load(new InputStreamReader(
                FramePhaseTest.class.getResourceAsStream("/fallback.json"), StandardCharsets.UTF_8));
    }

    @Test
    void phaseTracksMinecraftsClock() {
        assertEquals("dawn", FallbackPool.phaseOf(0));
        assertEquals("dawn", FallbackPool.phaseOf(23_500));
        assertEquals("day", FallbackPool.phaseOf(6_000));
        assertEquals("dusk", FallbackPool.phaseOf(12_500));
        assertEquals("night", FallbackPool.phaseOf(18_000));
        assertEquals("night", FallbackPool.phaseOf(13_500));
        // Ticks beyond one day, and negatives, still land somewhere sane.
        assertEquals("day", FallbackPool.phaseOf(24_000 + 6_000));
    }

    @Test
    void dyingAtNightDoesNotClaimDawn() {
        FallbackPool.EventDefault death = pool().defaultsFor("player_death");
        String atNight = death.frameFor(FallbackPool.phaseOf(18_000));
        assertFalse(atNight.toLowerCase().contains("dawn"), "was: " + atNight);
        assertTrue(atNight.toLowerCase().contains("dark"));
        assertEquals("You awaken at dawn.", death.frameFor(FallbackPool.phaseOf(0)));
        assertFalse(death.frameFor(FallbackPool.phaseOf(6_000)).toLowerCase().contains("dawn"));
    }

    @Test
    void everyEventStillHasAUsableFrameForEveryPhase() {
        FallbackPool pool = pool();
        for (String event : AssetsTest.ALL_EVENTS) {
            for (String phase : new String[]{"dawn", "day", "dusk", "night"}) {
                String frame = pool.defaultsFor(event).frameFor(phase);
                assertFalse(frame.isBlank(), event + " has no frame at " + phase);
            }
        }
    }
}
