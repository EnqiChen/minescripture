package dev.minescripture.event;

import dev.minescripture.trigger.PlayerStateManager;
import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches the day/night cycle for two moments:
 *
 * <ul>
 *   <li><b>first_nightfall</b> — the first night this player ever experiences,
 *       detected as world time crossing into night while they are online.</li>
 *   <li><b>survived_the_night</b> — dawn, but only for players the night actually
 *       came after: they were hit by something hostile, or fought something
 *       hostile. Hiding in a hole until morning is not survival, and neither is
 *       sleeping through it. Health at dawn is irrelevant — healing up after a
 *       fight does not undo having been in one.</li>
 * </ul>
 *
 * Runs as a repeating sync task; crossings are detected per world.
 */
public final class DayCycleClock implements Runnable {

    private static final long NIGHT_START = 13_000L;
    private static final long DAY_START = 12_000L;

    /** Damage worth calling "the night came after you". */
    private static final Set<String> NIGHT_THREATS = Set.of("mob", "explosion", "lightning");

    private final TriggerService service;
    private final PlayerStateManager playerState;
    private final Map<String, Long> lastTimeByWorld = new HashMap<>();
    private final Set<UUID> survivedSomething = ConcurrentHashMap.newKeySet();
    private final Set<UUID> presentInTheDark = ConcurrentHashMap.newKeySet();

    public DayCycleClock(TriggerService service, PlayerStateManager playerState) {
        this.service = service;
        this.playerState = playerState;
    }

    /**
     * Called by {@link SurvivalListener} whenever a player takes damage. Only
     * hostile damage during night hours counts toward surviving the night.
     */
    public void noteDamage(Player player, String cause) {
        noteDamage(player.getUniqueId(), cause, player.getWorld().getTime());
    }

    /**
     * Called when a player strikes a hostile. Fighting the night off counts as
     * surviving it too — otherwise the moment would only ever reach players who
     * got hurt, quietly rewarding bad play over good.
     */
    public void noteFoughtBack(Player player) {
        markIfNight(player.getUniqueId(), player.getWorld().getTime());
    }

    // Bukkit-free cores, so the rules are unit-testable without a server.

    void noteDamage(UUID playerId, String cause, long worldTime) {
        if (NIGHT_THREATS.contains(cause)) {
            markIfNight(playerId, worldTime);
        }
    }

    void markIfNight(UUID playerId, long worldTime) {
        if (worldTime >= NIGHT_START) {
            survivedSomething.add(playerId);
            presentInTheDark.add(playerId);
        }
    }

    boolean hasSurvivedSomething(UUID playerId) {
        return survivedSomething.contains(playerId);
    }

    boolean wasPresentInTheDark(UUID playerId) {
        return presentInTheDark.contains(playerId);
    }

    /** Dying means you did not survive the night — the death has its own moment. */
    public void noteDeath(UUID playerId) {
        forget(playerId);
    }

    /** Sleeping is opting out of the night, not enduring it; /verse sleep covers that. */
    public void noteSlept(UUID playerId) {
        forget(playerId);
    }

    public void forget(UUID playerId) {
        survivedSomething.remove(playerId);
        presentInTheDark.remove(playerId);
    }

    @Override
    public void run() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            long now = world.getTime();
            long last = lastTimeByWorld.getOrDefault(world.getName(), now);
            lastTimeByWorld.put(world.getName(), now);

            if (crossedIntoNight(last, now)) {
                for (Player player : world.getPlayers()) {
                    if (!playerState.milestoneDone(player.getUniqueId(), "first_nightfall")) {
                        service.submit(TriggerContext.of(player.getUniqueId(), "first_nightfall",
                                System.currentTimeMillis()));
                    }
                }
            }

            // Simply being out there in the dark is its own kind of night.
            if (now >= NIGHT_START) {
                world.getPlayers().forEach(p -> presentInTheDark.add(p.getUniqueId()));
            }

            if (crossedIntoDay(last, now)) {
                for (Player player : world.getPlayers()) {
                    UUID id = player.getUniqueId();
                    boolean fought = survivedSomething.contains(id);
                    boolean waited = presentInTheDark.contains(id);
                    if (fought) {
                        service.submit(TriggerContext.of(id, "survived_the_night",
                                System.currentTimeMillis()));
                    } else if (waited) {
                        service.submit(TriggerContext.of(id, "sheltered_till_dawn",
                                System.currentTimeMillis()));
                    }
                }
                // Anyone who logged off before dawn starts the next night clean.
                survivedSomething.clear();
                presentInTheDark.clear();
            }
        }
    }

    static boolean crossedIntoNight(long last, long now) {
        return last < NIGHT_START && now >= NIGHT_START && now < NIGHT_START + 2_000;
    }

    /**
     * Dawn: time wrapped past 24000 back to morning, or was moved forward out of
     * the night by sleeping or a time command.
     */
    static boolean crossedIntoDay(long last, long now) {
        return last >= NIGHT_START && now < DAY_START;
    }
}
