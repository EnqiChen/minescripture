package dev.minescripture.trigger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ephemeral narrative interpretation state for ONE player session. Never
 * persisted; cleared at logout (short reconnect grace handled by the store).
 * "MineScripture persists gameplay milestones, not narrative profiles."
 *
 * Material events bump {@code storyVersion} (powering the unchanged-story
 * check); mechanically repeated events collapse into aggregates instead —
 * the AFK-grinder guard's memory half.
 */
public final class StoryMemory {

    public record StoryEvent(String key, String detail, long at) {
    }

    public static final int MAX_EVENTS = 50;

    private final Deque<StoryEvent> events = new ArrayDeque<>();
    private final Map<String, Integer> aggregates = new LinkedHashMap<>();
    private final Map<String, Integer> themeCounts = new LinkedHashMap<>();
    private final Set<String> seenRefs = new LinkedHashSet<>();
    private final long sessionStartMillis;
    private long storyVersion = 0;
    private int deaths = 0;
    private boolean lostItems = false;
    private long quitAtMillis = -1;

    public StoryMemory(long sessionStartMillis) {
        this.sessionStartMillis = sessionStartMillis;
    }

    /** A material narrative event: enters the sliding window and bumps the story version. */
    public synchronized void recordEvent(String key, String detail, long at) {
        events.addLast(new StoryEvent(key, detail, at));
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
        storyVersion++;
        if ("player_death".equals(key)) {
            deaths++;
        }
    }

    /** A mechanically repeated event: aggregate only, no story-version bump. */
    public synchronized void recordCollapsed(String key) {
        aggregates.merge(key + "_repeats", 1, Integer::sum);
    }

    public synchronized void markLostItems() {
        lostItems = true;
    }

    public synchronized void addSeenRef(String ref) {
        seenRefs.add(ref);
    }

    public synchronized void countTheme(String theme) {
        themeCounts.merge(theme, 1, Integer::sum);
    }

    public synchronized List<StoryEvent> window(int n) {
        List<StoryEvent> all = new ArrayList<>(events);
        return all.size() <= n ? all : all.subList(all.size() - n, all.size());
    }

    public synchronized long storyVersion() {
        return storyVersion;
    }

    public synchronized int deaths() {
        return deaths;
    }

    public synchronized boolean lostItems() {
        return lostItems;
    }

    public synchronized Set<String> seenRefs() {
        return new LinkedHashSet<>(seenRefs);
    }

    public synchronized Map<String, Integer> themeCounts() {
        return new LinkedHashMap<>(themeCounts);
    }

    public synchronized Map<String, Integer> aggregates() {
        return new LinkedHashMap<>(aggregates);
    }

    public long minutesPlayed(long now) {
        return Math.max(0, (now - sessionStartMillis) / 60_000);
    }

    public synchronized void markQuit(long at) {
        quitAtMillis = at;
    }

    public synchronized void clearQuit() {
        quitAtMillis = -1;
    }

    /** True if this session ended more than graceMillis ago (safe to discard). */
    public synchronized boolean expired(long now, long graceMillis) {
        return quitAtMillis >= 0 && now - quitAtMillis > graceMillis;
    }

    /**
     * Anonymous gameplay context for the interpretation prompt: recent window +
     * aggregates + session stats + seen refs/themes. No names, no coordinates.
     */
    public synchronized Map<String, Object> toContext(long now, int windowSize) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        List<Map<String, String>> recent = new ArrayList<>();
        for (StoryEvent e : window(windowSize)) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("event", e.key());
            if (e.detail() != null && !e.detail().isBlank()) {
                entry.put("detail", e.detail());
            }
            entry.put("minutes_ago", String.valueOf(Math.max(0, (now - e.at()) / 60_000)));
            recent.add(entry);
        }
        ctx.put("recent_events", recent);
        ctx.put("total_deaths", deaths);
        ctx.put("minutes_played", minutesPlayed(now));
        ctx.put("lost_items", lostItems);
        if (!aggregates.isEmpty()) {
            ctx.put("aggregates", new LinkedHashMap<>(aggregates));
        }
        if (!seenRefs.isEmpty()) {
            ctx.put("seen_refs", new ArrayList<>(seenRefs));
        }
        if (!themeCounts.isEmpty()) {
            ctx.put("theme_counts", new LinkedHashMap<>(themeCounts));
        }
        return ctx;
    }
}
