package dev.minescripture.event;

import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * fellowship — NOT login: two players within 12 blocks for >= 60 s, first time
 * per pair per session. Repeating sync task every 10 s accumulates proximity;
 * separation resets the pair's clock.
 */
public final class FellowshipTracker implements Runnable {

    private static final double RADIUS_SQUARED = 12.0 * 12.0;
    private static final int REQUIRED_SECONDS = 60;
    private static final int TICK_SECONDS = 10;

    private final TriggerService service;
    private final Map<String, Integer> proximitySeconds = new HashMap<>();

    public FellowshipTracker(TriggerService service) {
        this.service = service;
    }

    @Override
    public void run() {
        Set<String> nearThisTick = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            List<Player> players = world.getPlayers();
            for (int i = 0; i < players.size(); i++) {
                for (int j = i + 1; j < players.size(); j++) {
                    Player a = players.get(i);
                    Player b = players.get(j);
                    if (a.getLocation().distanceSquared(b.getLocation()) > RADIUS_SQUARED) {
                        continue;
                    }
                    String pairKey = pairKey(a, b);
                    nearThisTick.add(pairKey);
                    int seconds = proximitySeconds.merge(pairKey, TICK_SECONDS, Integer::sum);
                    if (seconds >= REQUIRED_SECONDS) {
                        service.submit(new TriggerContext(a.getUniqueId(), "fellowship", null,
                                world.getName(), pairKey, System.currentTimeMillis(), Map.of()));
                        // Policy's once-per-pair-per-session takes it from here;
                        // keep the accumulator so we don't resubmit every tick.
                        proximitySeconds.put(pairKey, 0);
                    }
                }
            }
        }
        // Pairs that separated start over.
        proximitySeconds.keySet().retainAll(nearThisTick);
    }

    private static String pairKey(Player a, Player b) {
        String ua = a.getUniqueId().toString();
        String ub = b.getUniqueId().toString();
        return ua.compareTo(ub) < 0 ? ua + "|" + ub : ub + "|" + ua;
    }
}
