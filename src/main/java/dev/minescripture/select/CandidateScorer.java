package dev.minescripture.select;

import dev.minescripture.config.FallbackPool;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Scoring, not hard filtering: a valid-but-mismatched ref (Gen 1:1 on a death)
 * loses to a better candidate instead of being rejected. Semantic fit comes
 * from the FallbackPool's local {themes, tone} metadata; refs unknown to the
 * pool score neutral — the open canon stays open.
 *
 * Weights: emphasis match +10 · event fit +10 · resonance match +5 · tone
 * match +5 · arc-token match +3 each (cap +6) · no semantic connection at all
 * −5 · seen today −10 · unseen this session +5 · previously validated +2 ·
 * Gloo rank bonus +3/+2/+1.
 */
public final class CandidateScorer {

    private CandidateScorer() {
    }

    public static Optional<String> pickBest(List<String> canonicalRefs,
                                            Interpretation interp,
                                            String eventKey,
                                            FallbackPool pool,
                                            Set<String> seenToday,
                                            Set<String> seenSession,
                                            Set<String> reliable) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < canonicalRefs.size(); i++) {
            String ref = canonicalRefs.get(i);
            int score = score(ref, i, interp, eventKey, pool, seenToday, seenSession, reliable);
            if (score > bestScore) {
                bestScore = score;
                best = ref;
            }
        }
        return Optional.ofNullable(best);
    }

    public static int score(String ref,
                            int rank,
                            Interpretation interp,
                            String eventKey,
                            FallbackPool pool,
                            Set<String> seenToday,
                            Set<String> seenSession,
                            Set<String> reliable) {
        int score = 0;

        FallbackPool.VerseMeta meta = pool.metadata(ref);
        if (meta != null) {
            int semantic = 0;
            if (containsIgnoreCase(meta.themes(), interp.emphasis())) {
                semantic += 10;
            }
            if (containsIgnoreCase(meta.themes(), interp.resonance())) {
                semantic += 5;
            }
            int arcHits = 0;
            if (interp.emotionalArc() != null) {
                for (String token : interp.emotionalArc().toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                    if (token.length() > 2 && containsIgnoreCase(meta.themes(), token)) {
                        arcHits += 3;
                    }
                }
            }
            semantic += Math.min(arcHits, 6);
            if (meta.tone().equalsIgnoreCase(interp.tone())) {
                semantic += 5;
            }
            // Metadata exists but nothing connects: the Gen-1:1-on-a-death penalty.
            score += semantic > 0 ? semantic : -5;

            if (meta.events().contains(eventKey)) {
                score += 10;
            }
        }

        if (seenToday.contains(ref)) {
            score -= 10;
        }
        if (!seenSession.contains(ref)) {
            score += 5;
        }
        if (reliable.contains(ref)) {
            score += 2;
        }
        score += Math.max(0, 3 - rank); // Gloo's own ranking: +3 / +2 / +1

        return score;
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        if (value == null) {
            return false;
        }
        for (String s : set) {
            if (s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
