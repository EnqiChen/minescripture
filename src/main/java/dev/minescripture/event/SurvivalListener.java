package dev.minescripture.event;

import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.Bukkit;
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
import org.bukkit.plugin.Plugin;

import java.util.UUID;

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

    /** Long enough for lava or fire to finish the job if it is going to. */
    private static final long SURVIVAL_CONFIRM_TICKS = 60L;

    private final TriggerService service;
    private final DayCycleClock dayCycle;
    private final Plugin plugin;

    public SurvivalListener(TriggerService service, DayCycleClock dayCycle, Plugin plugin) {
        this.service = service;
        this.dayCycle = dayCycle;
        this.plugin = plugin;
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
            confirmSurvival(player.getUniqueId(), cause);
        }
    }

    /**
     * "You survived" is a claim about the future: at the instant of the hit you
     * have not survived anything yet. Lava and fire keep burning, so a player who
     * is on two hearts now may be dead a second from now — and answering that
     * with a verse about deliverance lands as mockery. Wait a beat and check.
     */
    private void confirmSurvival(UUID playerId, String cause) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || player.isDead() || player.getHealth() <= 0) {
                return; // they did not, in fact, survive it
            }
            service.submit(TriggerContext.of(playerId, "low_health_survival",
                    System.currentTimeMillis()).withCause(cause));
        }, SURVIVAL_CONFIRM_TICKS);
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
            // Sleeping skips the night rather than passing through it.
            dayCycle.noteSlept(event.getPlayer().getUniqueId());
            service.submit(TriggerContext.of(event.getPlayer().getUniqueId(), "sleep",
                    System.currentTimeMillis()));
        }
    }
}
