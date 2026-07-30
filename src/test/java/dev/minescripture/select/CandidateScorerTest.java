package dev.minescripture.select;

import dev.minescripture.config.FallbackPool;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scoring math against the real shipped fallback.json metadata. */
class CandidateScorerTest {

    private static final FallbackPool POOL = FallbackPool.load(new InputStreamReader(
            CandidateScorerTest.class.getResourceAsStream("/fallback.json"), StandardCharsets.UTF_8));

    private static final Set<String> NONE = Set.of();

    private static Interpretation death() {
        return new Interpretation("loss", "setback_to_hope", "comfort", "solemn",
                List.of(), null, null);
    }

    // The Gen-1:1-on-a-death trap: Gloo ranks a creation verse FIRST for a death
    // moment; semantic-fit scoring must let the fitting verse win anyway.
    @Test
    void semanticMismatchLosesToFittingVerseDespiteBetterRank() {
        List<String> candidates = List.of("GEN.1.25", "PSA.34.18");
        String best = CandidateScorer.pickBest(candidates, death(), "player_death",
                POOL, NONE, NONE, NONE).orElseThrow();
        assertEquals("PSA.34.18", best);

        int genesis = CandidateScorer.score("GEN.1.25", 0, death(), "player_death", POOL, NONE, NONE, NONE);
        int psalm = CandidateScorer.score("PSA.34.18", 1, death(), "player_death", POOL, NONE, NONE, NONE);
        assertTrue(psalm > genesis * 4, "a fitting verse should not merely edge ahead: " + psalm + " vs " + genesis);
        // -5 mismatch, +5 unseen, +3 rank, +4 brevity
        assertEquals(7, genesis, "no semantic connection → the penalty still bites");
    }

    // Novelty math: seen-today −10 AND losing the unseen +5 = a 15-point swing.
    @Test
    void seenTodayPenaltyIsExactlyFifteenPointsOfSwing() {
        int fresh = CandidateScorer.score("LAM.3.22-23", 0, death(), "player_death",
                POOL, NONE, NONE, NONE);
        int seen = CandidateScorer.score("LAM.3.22-23", 0, death(), "player_death",
                POOL, Set.of("LAM.3.22-23"), Set.of("LAM.3.22-23"), NONE);
        assertEquals(15, fresh - seen);
    }

    // "Every death = the same famous verse" must not happen: once today's verse
    // has been seen, a fresh equally-fitting candidate overtakes it.
    @Test
    void repeatVerseLosesToFreshAlternative() {
        List<String> candidates = List.of("ISA.43.2", "PSA.23.4");
        String firstPick = CandidateScorer.pickBest(candidates, death(), "player_death",
                POOL, NONE, NONE, NONE).orElseThrow();
        assertEquals("ISA.43.2", firstPick, "fresh session: Gloo's top rank wins");

        Set<String> seen = Set.of("ISA.43.2");
        String secondPick = CandidateScorer.pickBest(candidates, death(), "player_death",
                POOL, seen, seen, NONE).orElseThrow();
        assertEquals("PSA.23.4", secondPick, "seen-today verse yields to the fresh one");
    }

    // Open canon: refs outside the pool score neutral (no penalty), but a
    // pool-matched verse with real semantic fit outranks them.
    @Test
    void unknownRefsScoreNeutralNotNegative() {
        int unknown = CandidateScorer.score("OBA.1.1", 0, death(), "player_death",
                POOL, NONE, NONE, NONE);
        assertEquals(12, unknown,
                "unseen +5, rank +3, single-verse +4 — neutral on meaning, no metadata penalty");
        String best = CandidateScorer.pickBest(List.of("OBA.1.1", "LAM.3.22-23"), death(),
                "player_death", POOL, NONE, NONE, NONE).orElseThrow();
        assertEquals("LAM.3.22-23", best);
    }

    // A player reads this mid-game, so a one-verse passage is preferred over a
    // two-verse one that fits equally well. Requested in the prompt AND scored,
    // because asking the model nicely has never once been sufficient here.
    @Test
    void singleVersesArePreferredOverPairs() {
        int single = CandidateScorer.score("PSA.23.4", 0, death(), "player_death", POOL, NONE, NONE, NONE);
        int pair = CandidateScorer.score("LAM.3.22-23", 0, death(), "player_death", POOL, NONE, NONE, NONE);
        assertEquals(1, CandidateScorer.spanOf("PSA.23.4"));
        assertEquals(2, CandidateScorer.spanOf("LAM.3.22-23"));
        assertTrue(single > pair, "one verse should win a close call: " + single + " vs " + pair);
    }

    @Test
    void reliabilityBonusIsTwoPoints() {
        int plain = CandidateScorer.score("OBA.1.1", 0, death(), "player_death", POOL, NONE, NONE, NONE);
        int reliable = CandidateScorer.score("OBA.1.1", 0, death(), "player_death",
                POOL, NONE, NONE, Set.of("OBA.1.1"));
        assertEquals(2, reliable - plain);
    }

    @Test
    void eventFitRewardsTheMatchingMoment() {
        Interpretation sleep = new Interpretation("rest", "day_to_rest", "peace", "calm",
                List.of(), null, null);
        int forSleep = CandidateScorer.score("PSA.4.8", 0, sleep, "sleep", POOL, NONE, NONE, NONE);
        int forDeath = CandidateScorer.score("PSA.4.8", 0, death(), "player_death", POOL, NONE, NONE, NONE);
        assertTrue(forSleep > forDeath + 20,
                "same verse: right moment must dominate (event fit + full semantic match)");
    }

    @Test
    void gloosRankingBreaksTies() {
        // Two refs unknown to the pool: identical except rank.
        Interpretation interp = death();
        int first = CandidateScorer.score("OBA.1.1", 0, interp, "player_death", POOL, NONE, NONE, NONE);
        int second = CandidateScorer.score("JUD.1.2", 1, interp, "player_death", POOL, NONE, NONE, NONE);
        assertEquals(1, first - second, "adjacent ranks differ by exactly one point");
        assertEquals("OBA.1.1", CandidateScorer.pickBest(List.of("OBA.1.1", "JUD.1.2"),
                interp, "player_death", POOL, NONE, NONE, NONE).orElseThrow());
    }
}
