package dev.minescripture.event;

import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.Locale;

/**
 * Survival-flavored moments. Paper API compliance per the dev rules:
 * EntityDamageEvent checks getFinalDamage() > 0 AND that the player survived
 * (health − final damage in (0, 4.0]); modern consume/tame/bed events.
 */
public final class SurvivalListener implements Listener {

    private final TriggerService service;

    public SurvivalListener(TriggerService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getFinalDamage() <= 0) {
            return;
        }
        double after = player.getHealth() - event.getFinalDamage();
        if (after > 0 && after <= 4.0) {
            service.submit(TriggerContext.of(player.getUniqueId(), "low_health_survival",
                    System.currentTimeMillis()).withCause(Causes.classify(event.getCause())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.BREAD) {
            service.submit(TriggerContext.of(event.getPlayer().getUniqueId(), "eating_bread",
                    System.currentTimeMillis()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player player) {
            String animal = event.getEntity().getType().name().toLowerCase(Locale.ROOT);
            service.submit(TriggerContext.of(player.getUniqueId(), "taming",
                    System.currentTimeMillis()).withCause(animal));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            service.submit(TriggerContext.of(event.getPlayer().getUniqueId(), "sleep",
                    System.currentTimeMillis()));
        }
    }
}
