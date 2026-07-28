package dev.minescripture.select;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anti-repeat state for the scorer: which refs a player has seen (session and
 * trailing 24 h) plus the server-wide reliability set (refs that have passed
 * YouVersion validation before). Ephemeral; survives relogs only within the
 * 24 h window semantics, never written to disk.
 */
public final class SessionVerseMemory {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final Map<UUID, Map<String, Long>> seenAt = new HashMap<>();
    private final Set<String> reliable = new HashSet<>();

    public synchronized void markSeen(UUID player, String ref, long now) {
        seenAt.computeIfAbsent(player, k -> new HashMap<>()).put(ref, now);
    }

    public synchronized Set<String> seenToday(UUID player, long now) {
        Map<String, Long> mine = seenAt.get(player);
        Set<String> out = new HashSet<>();
        if (mine != null) {
            mine.entrySet().removeIf(e -> now - e.getValue() > DAY_MS);
            out.addAll(mine.keySet());
        }
        return out;
    }

    /** For the scorer's session-unseen bonus we use the same trailing set. */
    public synchronized Set<String> seenSession(UUID player, long now) {
        return seenToday(player, now);
    }

    public synchronized void markReliable(String ref) {
        reliable.add(ref);
    }

    public synchronized Set<String> reliableRefs() {
        return new HashSet<>(reliable);
    }
}
