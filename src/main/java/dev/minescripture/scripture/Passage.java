package dev.minescripture.scripture;

/**
 * One verbatim YouVersion passage. The text field is exactly what the API
 * returned (whitespace-normalized) — MineScripture never writes Scripture.
 */
public record Passage(
        String ref,          // canonical internal ref, e.g. LAM.3.22-23
        String display,      // human reference, e.g. "Lamentations 3:22-23"
        String text,         // verbatim YouVersion verse text
        String translation,  // abbreviation for the attribution line, e.g. "BSB"
        String copyright,    // full copyright notice for /verse + README
        String abridged      // nullable: same words, fewer of them (see Abridger)
) {
    public Passage(String ref, String display, String text, String translation, String copyright) {
        this(ref, display, text, translation, copyright, null);
    }

    public Passage withAbridged(String shortened) {
        return new Passage(ref, display, text, translation, copyright, shortened);
    }

    public boolean isAbridged() {
        return abridged != null && !abridged.isBlank();
    }

    /**
     * What to put in front of a player. Long passages use the abridgement when one
     * exists; /verse always replays the whole verse, so nothing is hidden.
     *
     * @param overChars length beyond which the shorter form is preferred; 0 disables
     */
    public String displayText(int overChars) {
        if (overChars > 0 && isAbridged() && text.length() > overChars) {
            return abridged;
        }
        return text;
    }
}
