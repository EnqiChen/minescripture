package dev.minescripture.event;

import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.ThunderChangeEvent;

import java.util.List;
import java.util.Map;

/**
 * World-scoped weather: one submit per storm (world-level cooldown in the
 * policy), broadcast to the whole world by MomentPresenter's recipients.
 */
public final class WorldListener implements Listener {

    private final TriggerService service;

    public WorldListener(TriggerService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThunder(ThunderChangeEvent event) {
        if (!event.toThunderState()) {
            return;
        }
        List<Player> players = event.getWorld().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        Player anchor = players.get(0);
        service.submit(new TriggerContext(anchor.getUniqueId(), "thunderstorm", null,
                event.getWorld().getName(), null, System.currentTimeMillis(), Map.of()));
    }
}
