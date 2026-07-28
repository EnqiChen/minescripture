package dev.minescripture.event;

import dev.minescripture.present.MomentPresenter;
import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * The centerpiece: selection begins AT DEATH (sudden path — the fresh
 * interpretation includes this death), presentation lands AT RESPAWN
 * ("You awaken at dawn."). The respawn screen absorbs the bounded wait.
 */
public final class DeathListener implements Listener {

    private final TriggerService service;
    private final MomentPresenter momentPresenter;
    private final DayCycleClock dayCycle;

    public DeathListener(TriggerService service, MomentPresenter momentPresenter, DayCycleClock dayCycle) {
        this.service = service;
        this.momentPresenter = momentPresenter;
        this.dayCycle = dayCycle;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        String cause = Causes.classifyLastDamage(player);
        dayCycle.noteDeath(player.getUniqueId()); // you did not survive this night
        if (!event.getKeepInventory() && !event.getDrops().isEmpty()) {
            service.story(player.getUniqueId()).markLostItems();
        }
        service.submit(TriggerContext.of(player.getUniqueId(), "player_death",
                System.currentTimeMillis()).withCause(cause));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        momentPresenter.flushPendingAfterRespawn(event.getPlayer().getUniqueId());
    }
}
