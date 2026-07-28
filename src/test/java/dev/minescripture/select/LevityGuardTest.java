package dev.minescripture.select;

import dev.minescripture.config.HumorPool;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generated text may never masquerade as Scripture. */
class LevityGuardTest {

    @Test
    void blocksChapterVerseCitations() {
        assertFalse(LevityGuard.valid("John 3:16"));
        assertFalse(LevityGuard.valid("As John 3:16 reminds us, love wins."));
        assertFalse(LevityGuard.valid("1 Cor 13:4 has thoughts about that."));
        assertFalse(LevityGuard.valid("See Psalm 23.4 for details"));
    }

    @Test
    void blocksBookPlusChapterEvenWithoutVerse() {
        assertFalse(LevityGuard.valid("Gen 1"));
        assertFalse(LevityGuard.valid("Try Rev 21 sometime."));
        assertFalse(LevityGuard.valid("Job 40 speaks of the Behemoth."));
        assertFalse(LevityGuard.valid("Read Genesis 3 before mining."));
    }

    @Test
    void blocksOversizedBlankAndNull() {
        assertFalse(LevityGuard.valid(null));
        assertFalse(LevityGuard.valid(""));
        assertFalse(LevityGuard.valid("   "));
        assertFalse(LevityGuard.valid("ha ".repeat(50))); // > 120 chars
    }

    @Test
    void blocksQuotationMarksButAllowsApostrophes() {
        assertFalse(LevityGuard.valid("As they say, \"pride goes before a fall.\""));
        assertFalse(LevityGuard.valid("“Let there be light” — and a torch."));
        assertTrue(LevityGuard.valid("Even Balaam's donkey knew better than that."));
    }

    @Test
    void allowsGentleBibleFlavoredHumorWithoutCitations() {
        assertTrue(LevityGuard.valid("Even Balaam's donkey would have seen that cactus."));
        assertTrue(LevityGuard.valid("Pride goes before a fall. So does standing too close to the edge."));
        assertTrue(LevityGuard.valid("It rained for forty days. You lasted four seconds."));
        // Book names WITHOUT trailing numbers are fine — Jonah/Job as words, not refs.
        assertTrue(LevityGuard.valid("Jonah spent three days inside a fish. You picked the pond."));
    }

    @Test
    void everyCuratedQuipPassesTheSameGuard() {
        HumorPool pool = HumorPool.load(new InputStreamReader(
                LevityGuardTest.class.getResourceAsStream("/humor.json"), StandardCharsets.UTF_8));
        pool.all().forEach(q ->
                assertTrue(LevityGuard.valid(q.text()), q.id() + " failed its own guard"));
    }
}
