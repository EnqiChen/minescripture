package dev.minescripture.journal;

import dev.minescripture.scripture.Passage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The verses of one session, per player — source for the written book
 * (/verse journal) and the markdown export at quit. Session-scoped like
 * StoryMemory: never persisted beyond the markdown file the player owns.
 */
public final class SessionJournal {

    public record Entry(long at, String eventKey, String refDisplay, String text, String translation) {
    }

    private final Map<UUID, List<Entry>> entries = new ConcurrentHashMap<>();

    public void add(UUID playerId, String eventKey, Passage passage) {
        entries.computeIfAbsent(playerId, k -> new ArrayList<>())
                .add(new Entry(System.currentTimeMillis(), eventKey,
                        passage.display(), passage.text(), passage.translation()));
    }

    public List<Entry> entries(UUID playerId) {
        return List.copyOf(entries.getOrDefault(playerId, List.of()));
    }

    public void clear(UUID playerId) {
        entries.remove(playerId);
    }
}
