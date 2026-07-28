package dev.minescripture.event;

import dev.minescripture.trigger.PlayerStateManager;
import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * first_nightfall: per-player first EXPERIENCED nightfall — world time crosses
 * ~13000 while the player is online and their persistent flag is unset. Runs
 * as a repeating sync task (every 5 s); crossing detection per world.
 */
public final class NightfallClock implements Runnable {

    private static final long NIGHT_START = 13_000L;

    private final TriggerService service;
    private final PlayerStateManager playerState;
    private final Map<String, Long> lastTimeByWorld = new HashMap<>();

    public NightfallClock(TriggerService service, PlayerStateManager playerState) {
        this.service = service;
        this.playerState = playerState;
    }

    @Override
    public void run() {
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            long now = world.getTime();
            long last = lastTimeByWorld.getOrDefault(world.getName(), now);
            lastTimeByWorld.put(world.getName(), now);
            boolean crossed = last < NIGHT_START && now >= NIGHT_START && now < NIGHT_START + 2_000;
            if (!crossed) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                if (!playerState.milestoneDone(player.getUniqueId(), "first_nightfall")) {
                    service.submit(TriggerContext.of(player.getUniqueId(), "first_nightfall",
                            System.currentTimeMillis()));
                }
            }
        }
    }
}
