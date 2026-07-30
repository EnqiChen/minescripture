package dev.minescripture.scripture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gloo may shorten a verse, but only by deleting. Every word that survives has to
 * be YouVersion's word in YouVersion's order — that is what keeps an abridged
 * verse honest, and it is checked rather than trusted.
 */
class AbridgerTest {

    private static final String VERSE =
            "He makes grass grow for the livestock and provides crops for man to cultivate, "
            + "bringing forth food from the earth: wine that gladdens the heart of man, "
            + "oil that makes his face to shine, and bread that sustains his heart.";

    @Test
    void acceptsAnHonestCut() {
        assertTrue(Abridger.isHonestCut(VERSE,
                "He makes grass grow for the livestock … bringing forth food from the earth"));
        assertTrue(Abridger.isHonestCut(VERSE, "bread that sustains his heart"),
                "a tail fragment is still a subsequence");
    }

    @Test
    void rejectsAnythingThatIsNotPurelyDeletion() {
        assertFalse(Abridger.isHonestCut(VERSE, "He grows grass for the animals"),
                "paraphrase must be refused");
        assertFalse(Abridger.isHonestCut(VERSE, "God makes grass grow for the livestock"),
                "an added word must be refused");
        assertFalse(Abridger.isHonestCut(VERSE, "bread that sustains … grass grow"),
                "reordering must be refused");
        assertFalse(Abridger.isHonestCut(VERSE, VERSE), "no gain is not an abridgement");
        assertFalse(Abridger.isHonestCut(VERSE, ""));
        assertFalse(Abridger.isHonestCut(VERSE, null));
    }

    @Test
    void punctuationAndCasingDoNotBlockAnOtherwiseHonestCut() {
        assertTrue(Abridger.isHonestCut(VERSE,
                "he makes grass grow … bread that sustains his heart."));
    }

    @Test
    void tidiesUpTheModelsFormatting() {
        assertEquals("He makes grass grow… his heart",
                Abridger.clean("  \"He makes grass grow... his heart\"  "));
    }

    @Test
    void aPassageFallsBackToTheWholeVerseUnlessShortened() {
        Passage full = new Passage("PSA.104.14-15", "Psalm 104:14-15", VERSE, "BSB", "");
        assertFalse(full.isAbridged());
        assertEquals(VERSE, full.displayText(160), "no abridgement means the whole verse");

        Passage cut = full.withAbridged("He makes grass grow … bread that sustains his heart");
        assertTrue(cut.isAbridged());
        assertEquals(cut.abridged(), cut.displayText(160), "long verse uses the shorter form");
        assertEquals(VERSE, cut.displayText(0), "0 disables abridgement entirely");
        assertEquals(VERSE, cut.text(), "the full verse is always still there for /verse");
    }
}
