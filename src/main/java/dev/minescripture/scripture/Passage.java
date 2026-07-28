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
        String copyright     // full copyright notice for /verse + README
) {
}
