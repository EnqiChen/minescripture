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
        assertTrue(genesis < 0 + psalm, "mismatch penalty + missing event fit must sink Genesis");
        assertTrue(genesis <= 5, "no semantic connection → penalty applies");
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
        assertEquals(8, unknown, "unseen +5 and rank +3 only — neutral, no metadata penalty");
        String best = CandidateScorer.pickBest(List.of("OBA.1.1", "LAM.3.22-23"), death(),
                "player_death", POOL, NONE, NONE, NONE).orElseThrow();
        assertEquals("LAM.3.22-23", best);
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
