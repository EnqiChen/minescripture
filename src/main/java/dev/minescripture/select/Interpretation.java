package dev.minescripture.select;

import java.util.List;

/**
 * The result of understanding a moment — normally produced by Gloo, but also
 * built from FallbackPool defaults (cold start / failure path). Pure data:
 * the HTTP client that produces it lives elsewhere.
 *
 * @param recommendedRefs ranked candidate refs (internal BOOK.CHAPTER.VERSE format
 *                        after normalization; raw model output before it)
 * @param quip            optional AI-generated light quip — only meaningful when
 *                        tone is "light"; must pass LevityGuard before any use
 * @param reasoning       server-log-only; never shown to players
 */
public record Interpretation(
        String resonance,
        String emotionalArc,
        String emphasis,
        String tone,
        List<String> recommendedRefs,
        String quip,
        String reasoning,
        String model
) {
    public Interpretation(String resonance, String emotionalArc, String emphasis, String tone,
                          List<String> recommendedRefs, String quip, String reasoning) {
        this(resonance, emotionalArc, emphasis, tone, recommendedRefs, quip, reasoning, null);
    }

    public Interpretation withModel(String routedModel) {
        return new Interpretation(resonance, emotionalArc, emphasis, tone,
                recommendedRefs, quip, reasoning, routedModel);
    }

    public boolean isLight() {
        return "light".equalsIgnoreCase(tone);
    }
}
