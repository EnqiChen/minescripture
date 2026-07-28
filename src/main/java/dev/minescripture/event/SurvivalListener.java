package dev.minescripture.event;

import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.Material;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.Locale;

/**
 * Survival-flavored moments. Paper API compliance per the dev rules:
 * EntityDamageEvent checks getFinalDamage() > 0 AND that the player survived
 * (health − final damage in (0, NEAR_DEATH_HEALTH]); modern consume/tame/bed
 * events.
 */
public final class SurvivalListener implements Listener {

    /** Three hearts or less, and still standing. */
    private static final double NEAR_DEATH_HEALTH = 6.0;

    private final TriggerService service;
    private final DayCycleClock dayCycle;

    public SurvivalListener(TriggerService service, DayCycleClock dayCycle) {
        this.service = service;
        this.dayCycle = dayCycle;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getFinalDamage() <= 0) {
            return;
        }
        String cause = Causes.classify(event.getCause());
        dayCycle.noteDamage(player, cause);

        double after = player.getHealth() - event.getFinalDamage();
        if (after > 0 && after <= NEAR_DEATH_HEALTH) {
            service.submit(TriggerContext.of(player.getUniqueId(), "low_health_survival",
                    System.currentTimeMillis()).withCause(cause));
        }
    }

    /**
     * Striking a hostile after dark counts toward having survived the night, so
     * a player who fights flawlessly and never takes a hit still gets the dawn
     * moment. Enemy (not Monster) is deliberate: it also covers phantoms, which
     * are the thing that hunts you at night, and slimes.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHitsHostile(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Enemy)) {
            return;
        }
        Player attacker = attackerOf(event);
        if (attacker != null) {
            dayCycle.noteFoughtBack(attacker);
        }
    }

    /** Melee hits come straight from the player; arrows and tridents come via the projectile. */
    private static Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
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
