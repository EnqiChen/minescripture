package dev.minescripture.trigger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sliding one-hour AI-call budget: "does this moment deserve AI?" is answered
 * upstream by TriggerPolicy; this is the last line of defense on token burn.
 * Over budget, only priority events (death, diamonds, first-times) still get AI.
 */
public final class AiBudgetGuard {

    private static final long HOUR_MS = 60L * 60L * 1000L;

    private final int playerPerHour;
    private final int serverPerHour;
    private final Map<UUID, Deque<Long>> perPlayer = new HashMap<>();
    private final Deque<Long> server = new ArrayDeque<>();

    public AiBudgetGuard(int playerPerHour, int serverPerHour) {
        this.playerPerHour = playerPerHour;
        this.serverPerHour = serverPerHour;
    }

    public synchronized boolean tryAcquire(UUID playerId, boolean priority, long now) {
        prune(now);
        Deque<Long> mine = perPlayer.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        boolean underBudget = mine.size() < playerPerHour && server.size() < serverPerHour;
        if (underBudget || priority) {
            mine.addLast(now);
            server.addLast(now);
            return true;
        }
        return false;
    }

    public synchronized int serverCallsLastHour(long now) {
        prune(now);
        return server.size();
    }

    public synchronized int playerCallsLastHour(UUID playerId, long now) {
        prune(now);
        Deque<Long> mine = perPlayer.get(playerId);
        return mine == null ? 0 : mine.size();
    }

    private void prune(long now) {
        long cutoff = now - HOUR_MS;
        while (!server.isEmpty() && server.peekFirst() < cutoff) {
            server.removeFirst();
        }
        perPlayer.values().forEach(q -> {
            while (!q.isEmpty() && q.peekFirst() < cutoff) {
                q.removeFirst();
            }
        });
    }
}
