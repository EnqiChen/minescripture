package dev.minescripture.select;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The alias dictionary must make LLM spelling inconsistency unable to fail a lookup. */
class RefNormalizerTest {

    private static String n(String raw) {
        Optional<String> result = RefNormalizer.normalize(raw);
        assertTrue(result.isPresent(), "expected normalizable: " + raw);
        return result.get();
    }

    private static void rejected(String raw) {
        assertTrue(RefNormalizer.normalize(raw).isEmpty(), "expected rejection: " + raw);
    }

    @Test
    void allThreeCanonicalInputFormats() {
        assertEquals("LAM.3.22-23", n("LAM.3.22-23"));
        assertEquals("LAM.3.22-23", n("LAM 3:22-23"));
        assertEquals("LAM.3.22-23", n("Lamentations 3:22-23"));
        assertEquals("LAM.3.22-23", n("lamentations 3:22-23"));
    }

    @Test
    void psalmSingularPluralAndAbbreviations() {
        assertEquals("PSA.23.1", n("Psalm 23:1"));
        assertEquals("PSA.23.1", n("Psalms 23:1"));
        assertEquals("PSA.23.1", n("Psa 23:1"));
        assertEquals("PSA.23.1", n("Ps 23:1"));
        assertEquals("PSA.23.1", n("PSA.23.1"));
    }

    @Test
    void revelationAndItsPopularMisname() {
        assertEquals("REV.21.4", n("Rev 21:4"));
        assertEquals("REV.21.4", n("Revelation 21:4"));
        assertEquals("REV.21.4", n("Revelations 21:4"));
    }

    @Test
    void numberedBookVariantsIncludingRomanNumerals() {
        assertEquals("1JN.4.19", n("1 John 4:19"));
        assertEquals("1JN.4.19", n("1Jn 4:19"));
        assertEquals("1JN.4.19", n("1JN.4.19"));
        assertEquals("1JN.4.19", n("I John 4:19"));
        assertEquals("1JN.4.19", n("First John 4:19"));
        assertEquals("2CO.4.8-9", n("2 Corinthians 4:8-9"));
        assertEquals("2CO.4.8", n("II Cor 4:8"));
        assertEquals("2TI.1.7", n("2nd Timothy 1:7"));
    }

    @Test
    void romanPrefixRuleDoesNotMangleIsaiah() {
        assertEquals("ISA.41.10", n("Isaiah 41:10"));
        assertEquals("ISA.41.10", n("Isa 41:10"));
    }

    @Test
    void songOfSolomonAliases() {
        assertEquals("SNG.2.1", n("Song of Solomon 2:1"));
        assertEquals("SNG.2.1", n("Song of Songs 2:1"));
        assertEquals("SNG.2.1", n("Songs 2:1"));
        assertEquals("SNG.2.1", n("SOS 2:1"));
    }

    @Test
    void rangesNormalizeAndCollapse() {
        assertEquals("JHN.3.16-17", n("JHN.3.16-17"));
        assertEquals("JHN.3.16-17", n("John 3:16–17")); // en dash
        assertEquals("JHN.3.16", n("JHN.3.16-16"));     // degenerate range collapses
    }

    // A player reads this mid-game in a few seconds, so two verses is the ceiling.
    @Test
    void spanCapAllowsTwoVersesRejectsMore() {
        assertEquals("LAM.3.22-23", n("Lamentations 3:22-23")); // exactly 2 — the cap
        assertEquals("JHN.3.16", n("John 3:16"));               // one is preferred
        rejected("Romans 5:3-5");                               // 3 verses — over the cap
        rejected("PSA.119.1-4");
        rejected("Psalm 23:1-6");                               // whole-psalm ranges never present
    }

    @Test
    void garbageIsRejected() {
        rejected(null);
        rejected("");
        rejected("   ");
        rejected("hello world");
        rejected("NotABook 3:16");
        rejected("John");        // no chapter/verse
        rejected("Gen 1");       // chapter only — validator needs a verse
    }

    @Test
    void chapterAndVerseSanityChecks() {
        rejected("PSA.151.1");   // Psalms has 150 chapters
        rejected("GEN.51.1");    // Genesis has 50
        rejected("JHN.22.1");    // John has 21
        rejected("GEN.0.1");
        rejected("JHN.3.0");
        rejected("JHN.3.16-2");  // backwards range
        assertEquals("PSA.119.176", n("Psalm 119:176")); // longest chapter is fine
    }

    @Test
    void bookCodeLookupPowersLevityGuard() {
        assertEquals("GEN", RefNormalizer.bookCode("Gen"));
        assertEquals("JOB", RefNormalizer.bookCode("Job"));
        assertEquals("1CO", RefNormalizer.bookCode("1 Cor"));
        assertTrue(RefNormalizer.isKnownBook("Revelations"));
        assertEquals(null, RefNormalizer.bookCode("creeper"));
    }
}
