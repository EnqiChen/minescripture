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

    public static boolean valid(String quip) {
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
        return true;
    }
}
