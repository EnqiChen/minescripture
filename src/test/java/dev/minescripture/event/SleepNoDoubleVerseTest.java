package dev.minescripture.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: sleeping produced two verses. The bed verse fired, then the clock's
 * next night tick re-flagged the player as "out in the dark" before the time skip
 * finished, so dawn awarded sheltered_till_dawn as well.
 */
class SleepNoDoubleVerseTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final long NIGHT = 15_000L;

    @Test
    void aClockTickAfterGoingToBedDoesNotReArmTheDawnMoment() {
        DayCycleClock c = new DayCycleClock(null, null);
        c.markIfNight(P1, NIGHT);            // out in the dark
        assertTrue(c.wasPresentInTheDark(P1));

        c.noteSlept(P1);                     // climbs into bed
        assertFalse(c.wasPresentInTheDark(P1));

        // The clock ticks again while the fade is still running and it is still night.
        c.markIfNight(P1, NIGHT);
        assertFalse(c.wasPresentInTheDark(P1), "going to bed must survive the next tick");
        assertFalse(c.hasSurvivedSomething(P1), "and must not count as surviving it either");
        assertTrue(c.sleptTonight(P1));
    }

    @Test
    void sleepingIsForgottenOnceMorningHasBeenHandled() {
        DayCycleClock c = new DayCycleClock(null, null);
        c.noteSlept(P1);
        assertTrue(c.sleptTonight(P1));
        c.forgetNight();
        assertFalse(c.sleptTonight(P1), "the next night starts clean");
        c.markIfNight(P1, NIGHT);
        assertTrue(c.wasPresentInTheDark(P1), "and can be out in the dark again");
    }
}
