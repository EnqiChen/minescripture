package dev.minescripture.select;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hard boundary between generated text and Scripture: an AI quip may never
 * even RESEMBLE a verse. Rejects anything containing chapter:verse shapes or a
 * known Bible book name followed by a number, plus length/blank caps. Any
 * rejection falls through to the curated human-written pool.
 */
public final class LevityGuard {

    public static final int MAX_LENGTH = 120;

    /** digit : digit or digit . digit anywhere — the universal citation shape. */
    private static final Pattern CHAPTER_VERSE = Pattern.compile("\\d+\\s*[:.]\\s*\\d+");

    /** a word (optionally 1-3 prefixed) followed by a number — "Gen 1", "Job 40". */
    private static final Pattern BOOK_THEN_NUMBER = Pattern.compile(
            "\\b([1-3]\\s*)?([A-Za-z]+)\\.?\\s+\\d");

    /** Double/curly quotes could make generated text read as quoted Scripture. */
    private static final Pattern QUOTE_MARKS = Pattern.compile("[\"“”«»]");

    private LevityGuard() {
    }

    /** Fewer than this many shared characters is coincidence, not an echo. */
    private static final int ECHO_MIN_LENGTH = 20;

    public static boolean valid(String quip) {
        return valid(quip, java.util.Set.of());
    }

    /**
     * @param mustNotEcho lines the model must not simply hand back: the prompt's
     *                    own worked example and every curated quip. Shown an
     *                    example, a model will sometimes return it verbatim, and
     *                    the result is indistinguishable in code from an original
     *                    line while being nothing of the kind — it was logged as
     *                    "written by Gloo" and would have been shown to judges as
     *                    proof the AI writes its own humour. Rejecting it sends
     *                    the moment to the curated pool, which is labelled
     *                    honestly. Every prompt rule here has a code-side twin;
     *                    this is the twin for "never repeat the example".
     */
    public static boolean valid(String quip, java.util.Collection<String> mustNotEcho) {
        if (quip == null) {
            return false;
        }
        String trimmed = quip.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return false;
        }
        if (QUOTE_MARKS.matcher(trimmed).find()) {
            return false;
        }
        if (CHAPTER_VERSE.matcher(trimmed).find()) {
            return false;
        }
        Matcher m = BOOK_THEN_NUMBER.matcher(trimmed);
        while (m.find()) {
            String candidate = (m.group(1) == null ? "" : m.group(1)) + m.group(2);
            if (RefNormalizer.isKnownBook(candidate)) {
                return false;
            }
        }
        return !echoes(trimmed, mustNotEcho);
    }

    /**
     * Containment rather than equality, because the echo came back both exactly
     * and with a word tacked on the end ("...that cactus" and "...that cactus
     * coming"). Either direction counts.
     */
    static boolean echoes(String quip, java.util.Collection<String> mustNotEcho) {
        String mine = fingerprint(quip);
        for (String other : mustNotEcho) {
            String theirs = fingerprint(other);
            if (theirs.length() < ECHO_MIN_LENGTH) {
                continue;
            }
            if (mine.contains(theirs) || theirs.contains(mine)) {
                return true;
            }
        }
        return false;
    }

    /** Letters and digits only: punctuation and spacing are not what makes it an echo. */
    static String fingerprint(String text) {
        return text == null ? ""
                : text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
