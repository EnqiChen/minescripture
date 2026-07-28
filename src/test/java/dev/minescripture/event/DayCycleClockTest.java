package dev.minescripture.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bukkit-free checks on the day/night crossing maths. */
class DayCycleClockTest {

    @Test
    void detectsNightfallOnceAsTimeCrossesIntoNight() {
        assertTrue(DayCycleClock.crossedIntoNight(12_900, 13_100));
        assertFalse(DayCycleClock.crossedIntoNight(13_100, 13_400), "already night");
        assertFalse(DayCycleClock.crossedIntoNight(11_000, 11_500), "still day");
        assertFalse(DayCycleClock.crossedIntoNight(12_900, 20_000), "stale tick, not the crossing");
    }

    @Test
    void detectsDawnOnWrapAndWhenTimeIsMovedForward() {
        assertTrue(DayCycleClock.crossedIntoDay(23_900, 100), "midnight wrapped round to morning");
        assertTrue(DayCycleClock.crossedIntoDay(14_000, 1_000), "slept through, or /time set day");
        assertFalse(DayCycleClock.crossedIntoDay(14_000, 18_000), "still night");
        assertFalse(DayCycleClock.crossedIntoDay(1_000, 5_000), "already day");
    }
}
