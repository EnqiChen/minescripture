package dev.minescripture.select;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kills the string-matching trap. Accepts anything Gloo plausibly emits —
 * "LAM.3.22-23", "LAM 3:22-23", "Lamentations 3:22-23", "Psalms 23:1",
 * "I John 4:19", "Song of Songs 2:1", "Revelations 21:4" — and converts it to
 * one canonical internal format: BOOK.CHAPTER.VERSE[-ENDVERSE].
 *
 * LLMs are inconsistent (Psalm/Psalms/Psa, Rev/Revelation/Revelations); the
 * alias dictionary makes sure inconsistency can never fail a lookup. Chapter
 * numbers are sanity-checked against real per-book chapter counts.
 */
public final class RefNormalizer {

    // book-part + chapter + verse [+ range-end]; separators : or . ; dashes -, –, —
    private static final Pattern REF = Pattern.compile(
            "^\\s*(.+?)\\s*[\\s.]\\s*(\\d{1,3})\\s*[:.]\\s*(\\d{1,3})(?:\\s*[-–—]\\s*(\\d{1,3}))?\\s*$");

    private static final Map<String, String> ALIASES = new HashMap<>();
    private static final Map<String, Integer> MAX_CHAPTER = new HashMap<>();
    private static final int MAX_VERSE = 176; // Psalm 119

    /**
     * Presentation policy, enforced here so every path inherits it: a moment's
     * passage is one verse, or two when the second completes the thought. A
     * player has seconds to read it, not a paragraph. Longer ranges are rejected
     * and the next candidate or curated default takes over.
     */
    static final int MAX_SPAN_VERSES = 2;

    private RefNormalizer() {
    }

    /** Normalize any supported spelling to canonical BOOK.C.V[-V2]; empty if unusable. */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher m = REF.matcher(raw.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String code = bookCode(m.group(1));
        if (code == null) {
            return Optional.empty();
        }
        int chapter = Integer.parseInt(m.group(2));
        int verse = Integer.parseInt(m.group(3));
        if (chapter < 1 || chapter > MAX_CHAPTER.getOrDefault(code, 150)
                || verse < 1 || verse > MAX_VERSE) {
            return Optional.empty();
        }
        if (m.group(4) == null) {
            return Optional.of(code + "." + chapter + "." + verse);
        }
        int end = Integer.parseInt(m.group(4));
        if (end == verse) {
            return Optional.of(code + "." + chapter + "." + verse);
        }
        if (end < verse || end > MAX_VERSE || end - verse + 1 > MAX_SPAN_VERSES) {
            return Optional.empty();
        }
        return Optional.of(code + "." + chapter + "." + verse + "-" + end);
    }

    /** USFM code for any book alias, or null if unknown. Used by LevityGuard too. */
    public static String bookCode(String rawBook) {
        if (rawBook == null) {
            return null;
        }
        return ALIASES.get(normalizeKey(rawBook));
    }

    public static boolean isKnownBook(String token) {
        return bookCode(token) != null;
    }

    /**
     * lowercase, roman/ordinal numbered-book prefixes → digits, strip
     * non-alphanumerics. Roman prefixes require a following separator so that
     * "Isaiah" (leading i) is never rewritten to "1saiah".
     */
    private static String normalizeKey(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^(iii|3rd|third)[\\s.]+", "3")
                .replaceFirst("^(ii|2nd|second)[\\s.]+", "2")
                .replaceFirst("^(i|1st|first)[\\s.]+", "1");
        return s.replaceAll("[^a-z0-9]", "");
    }

    private static void book(String code, int chapters, String... aliases) {
        MAX_CHAPTER.put(code, chapters);
        ALIASES.put(normalizeKey(code), code);
        for (String alias : aliases) {
            ALIASES.put(normalizeKey(alias), code);
        }
    }

    static {
        book("GEN", 50, "Genesis", "Gen", "Ge", "Gn");
        book("EXO", 40, "Exodus", "Exod", "Ex");
        book("LEV", 27, "Leviticus", "Lev", "Lv");
        book("NUM", 36, "Numbers", "Num", "Nm", "Nu", "Numb");
        book("DEU", 34, "Deuteronomy", "Deut", "Dt");
        book("JOS", 24, "Joshua", "Josh");
        book("JDG", 21, "Judges", "Judg", "Jdgs");
        book("RUT", 4, "Ruth", "Ru");
        book("1SA", 31, "1 Samuel", "1Sam", "1 Sam", "1Sm");
        book("2SA", 24, "2 Samuel", "2Sam", "2 Sam", "2Sm");
        book("1KI", 22, "1 Kings", "1Kgs", "1 Kgs", "1Kings");
        book("2KI", 25, "2 Kings", "2Kgs", "2 Kgs", "2Kings");
        book("1CH", 29, "1 Chronicles", "1Chr", "1 Chron", "1Chron");
        book("2CH", 36, "2 Chronicles", "2Chr", "2 Chron", "2Chron");
        book("EZR", 10, "Ezra");
        book("NEH", 13, "Nehemiah", "Neh");
        book("EST", 10, "Esther", "Esth");
        book("JOB", 42, "Job", "Jb");
        book("PSA", 150, "Psalms", "Psalm", "Psa", "Ps", "Pss", "Psm");
        book("PRO", 31, "Proverbs", "Proverb", "Prov", "Prv", "Pr");
        book("ECC", 12, "Ecclesiastes", "Eccl", "Eccles", "Qoheleth");
        book("SNG", 8, "Song of Songs", "Song of Solomon", "Songs", "Song", "SOS", "Canticles", "Cant");
        book("ISA", 66, "Isaiah", "Isa", "Is");
        book("JER", 52, "Jeremiah", "Jer", "Jr");
        book("LAM", 5, "Lamentations", "Lam", "Lament");
        book("EZK", 48, "Ezekiel", "Ezek", "Eze");
        book("DAN", 12, "Daniel", "Dan", "Dn");
        book("HOS", 14, "Hosea", "Hos");
        book("JOL", 3, "Joel", "Jl");
        book("AMO", 9, "Amos", "Am");
        book("OBA", 1, "Obadiah", "Obad", "Ob");
        book("JON", 4, "Jonah", "Jnh");
        book("MIC", 7, "Micah", "Mic", "Mc");
        book("NAM", 3, "Nahum", "Nah", "Na");
        book("HAB", 3, "Habakkuk", "Hab");
        book("ZEP", 3, "Zephaniah", "Zeph", "Zp");
        book("HAG", 2, "Haggai", "Hag", "Hg");
        book("ZEC", 14, "Zechariah", "Zech", "Zc");
        book("MAL", 4, "Malachi", "Mal", "Ml");
        book("MAT", 28, "Matthew", "Matt", "Mt");
        book("MRK", 16, "Mark", "Mk", "Mar");
        book("LUK", 24, "Luke", "Lk", "Lu");
        book("JHN", 21, "John", "Jn", "Jhn", "Joh");
        book("ACT", 28, "Acts", "Act", "Ac");
        book("ROM", 16, "Romans", "Rom", "Rm", "Ro");
        book("1CO", 16, "1 Corinthians", "1Cor", "1 Cor", "1Corinth");
        book("2CO", 13, "2 Corinthians", "2Cor", "2 Cor", "2Corinth");
        book("GAL", 6, "Galatians", "Gal", "Ga");
        book("EPH", 6, "Ephesians", "Eph");
        book("PHP", 4, "Philippians", "Phil", "Php", "Philip");
        book("COL", 4, "Colossians", "Col");
        book("1TH", 5, "1 Thessalonians", "1Thess", "1 Thess", "1Thes");
        book("2TH", 3, "2 Thessalonians", "2Thess", "2 Thess", "2Thes");
        book("1TI", 6, "1 Timothy", "1Tim", "1 Tim");
        book("2TI", 4, "2 Timothy", "2Tim", "2 Tim");
        book("TIT", 3, "Titus", "Tit");
        book("PHM", 1, "Philemon", "Phlm", "Phm", "Philem");
        book("HEB", 13, "Hebrews", "Heb");
        book("JAS", 5, "James", "Jas", "Jam", "Jms");
        book("1PE", 5, "1 Peter", "1Pet", "1 Pet", "1Pt");
        book("2PE", 3, "2 Peter", "2Pet", "2 Pet", "2Pt");
        book("1JN", 5, "1 John", "1Jn", "1 Jn", "1Jo", "1Jhn");
        book("2JN", 1, "2 John", "2Jn", "2 Jn", "2Jo", "2Jhn");
        book("3JN", 1, "3 John", "3Jn", "3 Jn", "3Jo", "3Jhn");
        book("JUD", 1, "Jude", "Jud", "Jde");
        book("REV", 22, "Revelation", "Revelations", "Rev", "Rv", "Apocalypse");
    }
}
