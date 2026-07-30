package dev.minescripture.scripture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Shortens a long passage by asking Gloo which words to drop.
 *
 * A player has a few seconds to read a verse mid-game, and some passages simply
 * do not fit — Psalm 104:14-15 runs to 224 characters. Choosing a shorter verse
 * solves most of it; this handles the rest.
 *
 * The integrity rule is that Gloo may only <em>delete</em>. Every word that
 * survives is YouVersion's word in YouVersion's order, and the result is checked
 * to be a subsequence of the original before it is ever used — so a model that
 * paraphrases, adds, reorders or "improves" the text is rejected outright and the
 * full verse is shown instead. Removals are marked with an ellipsis so a reader
 * can see that something was left out.
 *
 * Run ahead of time and cached, never on the path between a moment and its verse.
 */
public final class Abridger {

    /** A single character, so it reads as an omission rather than punctuation. */
    public static final String ELLIPSIS = "…";

    private static final String PROMPT = """
            You are shortening a Bible verse so it can be read in a few seconds during a game.

            RULES — these are absolute:
            - You may ONLY DELETE words. Never add a word, never change a word, never
              reorder anything, never fix grammar, never substitute a synonym.
            - Mark each place you removed words with a single … character.
            - Keep the part that carries the meaning. Drop subordinate clauses,
              repetition, and lists before anything else.
            - Target at most %d characters.
            - Output ONLY the shortened verse. No quotes, no explanation, no reference.

            Verse:
            %s
            """;

    private final Function<String, CompletableFuture<String>> ask;
    private final int targetChars;

    public Abridger(Function<String, CompletableFuture<String>> ask, int targetChars) {
        this.ask = ask;
        this.targetChars = targetChars;
    }

    /**
     * Returns the passage with an abridgement attached, or unchanged if the model
     * declined, rewrote rather than cut, or produced nothing shorter.
     */
    public CompletableFuture<Passage> abridge(Passage passage) {
        return ask.apply(PROMPT.formatted(targetChars, passage.text()))
                .thenApply(reply -> {
                    String candidate = clean(reply);
                    if (!isHonestCut(passage.text(), candidate)) {
                        return passage;
                    }
                    if (candidate.length() >= passage.text().length()) {
                        return passage; // no gain; keep the whole verse
                    }
                    return passage.withAbridged(candidate);
                })
                .exceptionally(err -> passage);
    }

    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\"", "").replace("...", ELLIPSIS)
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * True only if every word of the candidate appears in the original, in order —
     * i.e. the model deleted and nothing else. This is what lets an abridged verse
     * still be described as YouVersion's words.
     */
    static boolean isHonestCut(String original, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        List<String> want = words(candidate);
        List<String> have = words(original);
        if (want.isEmpty() || want.size() >= have.size()) {
            return false;
        }
        int i = 0;
        for (String w : want) {
            while (i < have.size() && !have.get(i).equals(w)) {
                i++;
            }
            if (i == have.size()) {
                return false; // a word that is not in the original, or out of order
            }
            i++;
        }
        return true;
    }

    /** Comparable word list: ellipses dropped, punctuation and case ignored. */
    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String token : text.replace(ELLIPSIS, " ").toLowerCase(Locale.ROOT).split("\\s+")) {
            String w = token.replaceAll("[^a-z0-9']", "");
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }
}
