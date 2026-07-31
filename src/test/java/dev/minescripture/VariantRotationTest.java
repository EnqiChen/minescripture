package dev.minescripture;

import dev.minescripture.config.FallbackPool;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        // Accent markers are styling, so they are not part of what has to fit.
        for (FallbackPool.Variant v : pool().defaultsFor("rejoin").variants()) {
            String onScreen = FallbackPool.plain(v.title());
            assertTrue(onScreen.length() <= 28, "too long for a title: " + onScreen);
            assertFalse(v.frame().isBlank());
        }
    }

    /**
     * A title may gild one word — "Welcome, {Sojourner}" — and the braces are
     * markup, not text. An unbalanced or greedy marker would put a stray brace on
     * screen in the one frame a first-time player is guaranteed to see.
     */
    @Test
    void accentMarkersAreBalancedAndNeverReachThePlayerAsText() {
        FallbackPool pool = pool();
        for (String event : List.of("first_join", "rejoin")) {
            FallbackPool.EventDefault defaults = pool.defaultsFor(event);
            List<String> titles = new ArrayList<>();
            titles.add(defaults.titleOrShortFrame());
            defaults.variants().forEach(v -> titles.add(v.title()));

            for (String title : titles) {
                long opens = title.chars().filter(c -> c == '{').count();
                long closes = title.chars().filter(c -> c == '}').count();
                assertEquals(opens, closes, "unbalanced accent markers: " + title);
                assertTrue(opens <= 1, "at most one accented span per title: " + title);
                assertFalse(FallbackPool.plain(title).contains("{"), title);
                assertFalse(FallbackPool.plain(title).contains("}"), title);
            }
            assertFalse(defaults.frame().contains("{"),
                    "chat prose is never styled this way: " + defaults.frame());
        }
    }

    @Test
    void theWelcomeIsAddressedToSomeone() {
        // "Sojourner" is capitalised because the line addresses the player directly,
        // the same reason "Welcome, Traveler" is. It stays lowercase where it is an
        // ordinary noun rather than a form of address.
        FallbackPool pool = pool();
        assertEquals("Welcome, {Sojourner}", pool.defaultsFor("first_join").titleOrShortFrame());
        assertEquals("Welcome, Sojourner.", pool.defaultsFor("first_join").frame());
        assertTrue(pool.defaultsFor("rejoin").variants().stream()
                        .anyMatch(v -> v.title().contains("{sojourner}")),
                "the third-person greeting keeps the common noun lowercase");
    }

    @Test
    void eventsWithoutVariantsStillGetTheirBaseWording() {
        FallbackPool.EventDefault death = pool().defaultsFor("player_death");
        FallbackPool.Variant v = death.variant(new Random(3), null);
        assertEquals("You awaken", v.title(), "falls back to the configured title");
        assertFalse(v.frame().isBlank());
    }
}
