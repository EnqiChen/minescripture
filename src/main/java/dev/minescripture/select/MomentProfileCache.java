package dev.minescripture.select;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached interpretations per player+event — the predictable path serves from
 * here instantly while Gloo restocks in the background. Pure in-memory data;
 * no HTTP.
 */
public final class MomentProfileCache {

    public record Entry(Interpretation interpretation, long storyVersion, long at) {
    }

    private final Map<UUID, Map<String, Entry>> cache = new ConcurrentHashMap<>();

    public Entry get(UUID playerId, String eventKey) {
        Map<String, Entry> mine = cache.get(playerId);
        return mine == null ? null : mine.get(eventKey);
    }

    public void put(UUID playerId, String eventKey, Interpretation interpretation, long storyVersion, long at) {
        cache.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(eventKey, new Entry(interpretation, storyVersion, at));
    }

    public void clear(UUID playerId) {
        cache.remove(playerId);
    }
}
