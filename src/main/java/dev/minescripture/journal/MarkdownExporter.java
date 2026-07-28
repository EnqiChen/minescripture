package dev.minescripture.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Writes the session's verses to a markdown file at quit — the take-home
 * artifact of a session's Scripture moments.
 */
public final class MarkdownExporter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());

    private MarkdownExporter() {
    }

    /** Returns the written file, or null when there was nothing to export. */
    public static Path export(Path dataDir, UUID playerId, List<SessionJournal.Entry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        try {
            Path dir = dataDir.resolve("journals");
            Files.createDirectories(dir);
            Path file = dir.resolve(playerId + "-" + DATE.format(Instant.now()) + ".md");
            StringBuilder md = new StringBuilder("# Session Verses\n\n");
            for (SessionJournal.Entry e : entries) {
                md.append("**").append(e.refDisplay()).append("**");
                if (!e.translation().isBlank()) {
                    md.append(" (").append(e.translation()).append(")");
                }
                md.append(" · ").append(e.eventKey()).append(" · ")
                        .append(TIME.format(Instant.ofEpochMilli(e.at()))).append("\n\n")
                        .append("> ").append(e.text()).append("\n\n");
            }
            md.append("---\n*Scripture text: YouVersion. Recorded by MineScripture.*\n");
            Files.writeString(file, md.toString());
            return file;
        } catch (IOException e) {
            return null;
        }
    }
}
