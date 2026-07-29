package dev.minescripture.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bukkit-free checks on the day/night crossing maths and the survival flag. */
class DayCycleClockTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final long NIGHT = 15_000L;
    private static final long DAY = 4_000L;

    private static DayCycleClock clock() {
        return new DayCycleClock(null, null);
    }

    @Test
    void hostileDamageAtNightMarksTheNightAsSurvived() {
        DayCycleClock c = clock();
        c.noteDamage(P1, "mob", NIGHT);
        assertTrue(c.hasSurvivedSomething(P1));
    }

    @Test
    void healingAfterwardsDoesNotUndoIt() {
        DayCycleClock c = clock();
        c.noteDamage(P1, "mob", NIGHT);
        // Nothing about health is consulted; the flag is about what happened,
        // not about how many hearts are left when the sun comes up.
        assertTrue(c.hasSurvivedSomething(P1), "a full health bar at dawn still earns the moment");
    }

    @Test
    void fightingBackCountsEvenWithoutTakingAHit() {
        DayCycleClock c = clock();
        c.markIfNight(P1, NIGHT); // what noteFoughtBack() delegates to
        assertTrue(c.hasSurvivedSomething(P1), "flawless play should not be punished");
    }

    @Test
    void daytimeScrapsAndNonHostileHurtsDoNotCount() {
        DayCycleClock c = clock();
        c.noteDamage(P1, "mob", DAY);
        assertFalse(c.hasSurvivedSomething(P1), "a daytime fight is not surviving the night");
        c.noteDamage(P1, "fall", NIGHT);
        assertFalse(c.hasSurvivedSomething(P1), "falling off a ledge is not the night hunting you");
        c.noteDamage(P1, "cactus", NIGHT);
        assertFalse(c.hasSurvivedSomething(P1));
    }

    @Test
    void beingOutInTheDarkCountsAsWaitingEvenWithoutAFight() {
        DayCycleClock c = clock();
        c.markIfNight(P1, NIGHT);
        assertTrue(c.wasPresentInTheDark(P1));
    }

    @Test
    void sleepingOptsOutOfBothNightMoments() {
        DayCycleClock c = clock();
        c.noteDamage(P1, "mob", NIGHT);
        c.noteSlept(P1);
        assertFalse(c.hasSurvivedSomething(P1), "sleeping skips the night rather than passing through it");
        assertFalse(c.wasPresentInTheDark(P1));
    }

    @Test
    void dyingClearsTheWaitingFlagToo() {
        DayCycleClock c = clock();
        c.markIfNight(P1, NIGHT);
        c.noteDeath(P1);
        assertFalse(c.wasPresentInTheDark(P1), "you did not wait out a night you died in");
    }

    @Test
    void dyingMeansYouDidNotSurviveTheNight() {
        DayCycleClock c = clock();
        c.noteDamage(P1, "mob", NIGHT);
        c.noteDeath(P1);
        assertFalse(c.hasSurvivedSomething(P1), "the death gets its own moment instead");
    }

    @Test
    void detectsNightfallOnceAsTimeCrossesIntoNight() {
        assertTrue(DayCycleClock.crossedIntoNight(12_900, 13_100));
        assertFalse(DayCycleClock.crossedIntoNight(13_100, 13_400), "already night");
        assertFalse(DayCycleClock.crossedIntoNight(11_000, 11_500), "still day");
        assertTrue(DayCycleClock.crossedIntoNight(12_900, 20_000), "/time set night must still count");
    }

    @Test
    void detectsDawnOnWrapAndWhenTimeIsMovedForward() {
        assertTrue(DayCycleClock.crossedIntoDay(23_900, 100), "midnight wrapped round to morning");
        assertTrue(DayCycleClock.crossedIntoDay(14_000, 1_000), "slept through, or /time set day");
        assertFalse(DayCycleClock.crossedIntoDay(14_000, 18_000), "still night");
        assertFalse(DayCycleClock.crossedIntoDay(1_000, 5_000), "already day");
    }
}
