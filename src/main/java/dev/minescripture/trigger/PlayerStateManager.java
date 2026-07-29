package dev.minescripture.trigger;

import com.google.gson.reflect.TypeToken;
import dev.minescripture.util.JsonUtil;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persisted gameplay progression state — milestone flags and preferences only,
 * like vanilla advancement flags. This is the ONLY persisted per-player store;
 * narrative state (StoryMemory) is session-ephemeral by design.
 */
public final class PlayerStateManager {

    /** Mutable per-player persisted flags. */
    public static final class PlayerFlags {
        public boolean firstJoinSeen = false;
        public boolean nightfallSeen = false;
        public boolean diamondsFound = false;
        public boolean muted = false;
        public String translation = "";
    }

    private static final Type MAP_TYPE = new TypeToken<Map<String, PlayerFlags>>() {
    }.getType();

    private final Path file;
    private final Map<String, PlayerFlags> players = new ConcurrentHashMap<>();

    public PlayerStateManager(Path dataDir) {
        this.file = dataDir.resolve("players.json");
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, PlayerFlags> loaded = JsonUtil.GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                players.putAll(loaded);
            }
        } catch (IOException | RuntimeException e) {
            // Corrupt state file: start fresh rather than fail the plugin.
            players.clear();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                JsonUtil.GSON.toJson(players, MAP_TYPE, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save player state to " + file, e);
        }
    }

    public PlayerFlags flags(UUID playerId) {
        return players.computeIfAbsent(playerId.toString(), k -> new PlayerFlags());
    }

    public boolean milestoneDone(UUID playerId, String eventKey) {
        PlayerFlags f = flags(playerId);
        return switch (eventKey) {
            case "first_join" -> f.firstJoinSeen;
            case "first_nightfall" -> f.nightfallSeen;
            case "found_diamonds" -> f.diamondsFound;
            default -> false;
        };
    }

    /** Marks a persistent milestone as fired and saves immediately (small file, rare events). */
    public void markMilestone(UUID playerId, String eventKey) {
        PlayerFlags f = flags(playerId);
        switch (eventKey) {
            case "first_join" -> f.firstJoinSeen = true;
            case "first_nightfall" -> f.nightfallSeen = true;
            case "found_diamonds" -> f.diamondsFound = true;
            default -> {
                return;
            }
        }
        save();
    }

    /**
     * Clears the once-ever flags so a first-time moment can fire again. Needed
     * for rehearsal and filming: without it, a single test run permanently
     * consumes first_join / first_nightfall / found_diamonds for that account.
     */
    public void resetMilestones(UUID playerId) {
        PlayerFlags f = flags(playerId);
        f.firstJoinSeen = false;
        f.nightfallSeen = false;
        f.diamondsFound = false;
        save();
    }

    public boolean isMuted(UUID playerId) {
        return flags(playerId).muted;
    }

    public void setMuted(UUID playerId, boolean muted) {
        flags(playerId).muted = muted;
        save();
    }
}
