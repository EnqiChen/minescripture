package dev.minescripture;

import dev.minescripture.config.FallbackPool;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A moment that recurs needs more than one way of announcing itself. The verse
 * already rotates; a fixed line above it goes stale by the third session.
 */
class VariantRotationTest {

    private static FallbackPool pool() {
        return FallbackPool.load(new InputStreamReader(
                VariantRotationTest.class.getResourceAsStream("/fallback.json"),
                StandardCharsets.UTF_8));
    }

    @Test
    void aRecurringWelcomeNeverRepeatsBackToBack() {
        FallbackPool.EventDefault rejoin = pool().defaultsFor("rejoin");
        Random random = new Random(1);
        String previous = null;
        for (int session = 0; session < 40; session++) {
            FallbackPool.Variant v = rejoin.variant(random, previous);
            assertNotNull(v.title());
            assertFalse(v.title().equals(previous),
                    "session " + session + " repeated the previous greeting: " + v.title());
            previous = v.title();
        }
    }

    @Test
    void itActuallyUsesTheWholeSetRatherThanAlternating() {
        FallbackPool.EventDefault rejoin = pool().defaultsFor("rejoin");
        Random random = new Random(7);
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (int i = 0; i < 60; i++) {
            FallbackPool.Variant v = rejoin.variant(random, previous);
            seen.add(v.title());
            previous = v.title();
        }
        assertEquals(rejoin.variants().size(), seen.size(),
                "every configured greeting should turn up over time");
    }

    @Test
    void everyGreetingFitsTheCentredLine() {
        // The line that overflowed a Minecraft title was 40 characters at ~4x scale.
        for (FallbackPool.Variant v : pool().defaultsFor("rejoin").variants()) {
            assertTrue(v.title().length() <= 28, "too long for a title: " + v.title());
            assertFalse(v.frame().isBlank());
        }
    }

    @Test
    void eventsWithoutVariantsStillGetTheirBaseWording() {
        FallbackPool.EventDefault death = pool().defaultsFor("player_death");
        FallbackPool.Variant v = death.variant(new Random(3), null);
        assertEquals("You awaken", v.title(), "falls back to the configured title");
        assertFalse(v.frame().isBlank());
    }
}
