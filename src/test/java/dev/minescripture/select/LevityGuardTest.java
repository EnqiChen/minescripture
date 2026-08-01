package dev.minescripture.select;

import dev.minescripture.config.HumorPool;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    /**
     * The prompt carries a worked example so the model knows the shape. Gloo
     * handed it straight back — "Even Balaam's donkey would have seen that
     * cactus.", character for character, and once with a word tacked on the end.
     * The code logged it as a line written by Gloo, which was true of the API
     * call and false to anyone reading it. An echo is not authorship.
     */
    @Test
    void aQuipThatMerelyRepeatsWhatWeShowedTheModelIsNotItsOwn() {
        List<String> shown = List.of(
                MomentInterpreter.QUIP_EXAMPLE,
                "Even Balaam's donkey would have seen that cactus.");

        assertFalse(LevityGuard.valid("Even Balaam's donkey would have seen that cactus.", shown),
                "verbatim echo");
        assertFalse(LevityGuard.valid("even balaams donkey would have seen that cactus", shown),
                "punctuation and casing do not make it original");
        assertFalse(LevityGuard.valid("Even Balaam's donkey would have seen that cactus coming", shown),
                "a word on the end does not make it original either");
        assertFalse(LevityGuard.valid(MomentInterpreter.QUIP_EXAMPLE, shown),
                "the prompt's own example must never reach a player as AI-written");

        assertTrue(LevityGuard.valid(
                        "Even Balaam's donkey learned to avoid the angel after one encounter.", shown),
                "same reference, genuinely different line — this one Gloo really wrote");
        assertTrue(LevityGuard.valid(
                        "Job had three friends. You have seven cacti. At least his sat quietly.", shown));
    }

    @Test
    void shortIncidentalOverlapIsNotAnEcho() {
        // Guarding on containment could otherwise reject anything sharing a stub.
        assertTrue(LevityGuard.valid("Jonah ran from a fish, you ran off a cliff.", List.of("Jonah")));
    }
}
