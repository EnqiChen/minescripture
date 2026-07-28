package dev.minescripture.event;

import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Locale;

/** Maps Bukkit damage causes to the story/levity cause vocabulary. */
final class Causes {

    private Causes() {
    }

    static String classify(EntityDamageEvent.DamageCause cause) {
        if (cause == null) {
            return "unknown";
        }
        return switch (cause) {
            case CONTACT -> "cactus";
            case FALL -> "fall";
            case LAVA -> "lava";
            case FIRE, FIRE_TICK, HOT_FLOOR -> "fire";
            case DROWNING -> "drown";
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, THORNS -> "mob";
            case STARVATION -> "starve";
            case VOID -> "void";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> "explosion";
            case FALLING_BLOCK, SUFFOCATION -> "crush";
            case LIGHTNING -> "lightning";
            case FREEZE -> "freeze";
            case POISON, WITHER -> "poison";
            case MAGIC, DRAGON_BREATH, SONIC_BOOM -> "magic";
            default -> cause.name().toLowerCase(Locale.ROOT);
        };
    }

    static String classifyLastDamage(org.bukkit.entity.Player player) {
        EntityDamageEvent last = player.getLastDamageCause();
        return last == null ? "unknown" : classify(last.getCause());
    }
}
