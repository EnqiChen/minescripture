package dev.minescripture.trigger;

import java.util.Map;
import java.util.UUID;

/**
 * One candidate moment. Bukkit-free: listeners translate Bukkit events into this.
 * No usernames — players are UUIDs here and stay anonymous toward AI services.
 *
 * @param cause    optional detail (death cause class like "cactus"/"fall"/"mob";
 *                 used for levity routing and story detail)
 * @param worldKey world identifier for world-scoped cooldowns (thunderstorm)
 * @param pairKey  canonical "uuidA|uuidB" (sorted) for fellowship once-per-pair
 * @param at       event time millis — the policy's clock (deterministic in tests)
 */
public record TriggerContext(
        UUID playerId,
        String eventKey,
        String cause,
        String worldKey,
        String pairKey,
        long at,
        Map<String, String> extras
) {
    public static TriggerContext of(UUID playerId, String eventKey, long at) {
        return new TriggerContext(playerId, eventKey, null, null, null, at, Map.of());
    }

    public TriggerContext withCause(String newCause) {
        return new TriggerContext(playerId, eventKey, newCause, worldKey, pairKey, at, extras);
    }
}
