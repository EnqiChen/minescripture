package dev.minescripture;

import dev.minescripture.config.EventSpec;
import dev.minescripture.config.EventSpecs;
import dev.minescripture.config.FallbackPool;
import dev.minescripture.config.HumorPool;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates the shipped JSON assets — the curation is part of the product. */
class AssetsTest {

    static final List<String> ALL_EVENTS = List.of(
            "first_join", "rejoin", "first_nightfall", "survived_the_night", "sheltered_till_dawn",
            "eating_bread", "taming",
            "low_health_survival", "found_diamonds", "player_death", "sleep", "thunderstorm",
            "fellowship");

    static Reader resource(String name) {
        return new InputStreamReader(AssetsTest.class.getResourceAsStream(name), StandardCharsets.UTF_8);
    }

    static EventSpecs realSpecs() {
        return EventSpecs.load(resource("/events.json"));
    }

    static FallbackPool realPool() {
        return FallbackPool.load(resource("/fallback.json"));
    }

    @Test
    void fallbackPoolHasExactly52Verses() {
        assertEquals(52, realPool().verses().size());
    }

    /**
     * The welcome calls the player a sojourner and quotes a verse to say why, so
     * that verse has to actually contain the word. BSB and NIV both render
     * Psalm 119:19 as "stranger"; ASV is the only readable translation available
     * to us that says "sojourner", so this one verse is pinned to it.
     */
    @Test
    void theSojournerVerseIsPinnedToTheTranslationThatUsesTheWord() {
        FallbackPool pool = realPool();
        FallbackPool.VerseMeta meta = pool.metadata("PSA.119.19");
        assertNotNull(meta, "the welcome's explaining verse must be in the pool");
        assertEquals(12, meta.bibleId(), "pinned to ASV");
        assertEquals(12, pool.bibleIdFor("PSA.119.19", 3034), "the pin overrides the configured Bible");
        assertEquals(3034, pool.bibleIdFor("JER.29.11", 3034), "every other verse follows the config");
        assertEquals(3034, pool.bibleIdFor("SOMETHING.GLOO.PICKED", 3034),
                "a ref outside the pool takes the default");
        assertEquals(1, pool.pinnedVerses().size(), "pinning is the rare exception, not a habit");
        assertEquals("PSA.119.19", realPool().defaultsFor("first_join").refs().get(0),
                "it leads the arrival defaults, so a Gloo outage still explains the word");
    }

    @Test
    void everyEventHasDefaultsAndDefaultRefsExistInPool() {
        FallbackPool pool = realPool();
        for (String event : ALL_EVENTS) {
            FallbackPool.EventDefault d = pool.defaultsFor(event);
            assertNotNull(d, "missing defaults for " + event);
            assertFalse(d.refs().isEmpty(), "no default refs for " + event);
            assertFalse(d.frame().isBlank(), "no frame for " + event);
            assertNotNull(d.interpretation().emphasis(), "no emphasis for " + event);
            for (String ref : d.refs()) {
                assertNotNull(pool.metadata(ref), "default ref " + ref + " for " + event + " missing from pool");
            }
        }
    }

    @Test
    void everyVerseHasThemesAndKnownTone() {
        FallbackPool pool = realPool();
        Set<String> tones = Set.of("solemn", "warm", "awe", "calm", "encouraging", "light");
        pool.verses().values().forEach(v -> {
            assertFalse(v.themes().isEmpty(), v.ref() + " has no themes");
            assertTrue(tones.contains(v.tone()), v.ref() + " has unknown tone " + v.tone());
            assertFalse(v.events().isEmpty(), v.ref() + " mapped to no events");
        });
    }

    @Test
    void humorPoolHasExactly15CleanQuips() {
        HumorPool humor = HumorPool.load(resource("/humor.json"));
        assertEquals(15, humor.all().size());
        // No quip may even resemble a verse citation — LevityGuard's core premise.
        Pattern citation = Pattern.compile("\\b(?:[1-3]\\s?)?[A-Z][a-z]+\\.?\\s?\\d+[:.]\\d+");
        humor.all().forEach(q -> {
            assertFalse(q.text().isBlank());
            assertTrue(q.text().length() <= 140, q.id() + " too long");
            assertFalse(citation.matcher(q.text()).find(), q.id() + " looks like a verse citation");
        });
    }

    @Test
    void eventSpecsMatchThePlan() {
        EventSpecs specs = realSpecs();
        assertEquals(13, specs.all().size());
        for (String event : ALL_EVENTS) {
            assertNotNull(specs.get(event), "missing spec " + event);
        }
        EventSpec death = specs.get("player_death");
        assertEquals(EventSpec.Path.SUDDEN, death.path());
        assertEquals(EventSpec.Tier.MAJOR, death.tier());
        assertTrue(death.priority());
        assertEquals(60, death.cooldownSeconds(), "base gap; TriggerPolicy escalates it within a run");

        EventSpec lowHealth = specs.get("low_health_survival");
        assertEquals(EventSpec.Path.SUDDEN, lowHealth.path());
        assertEquals(600, lowHealth.cooldownSeconds(), "AFK-grinder guard needs the 10-min cooldown");

        EventSpec diamonds = specs.get("found_diamonds");
        assertEquals(EventSpec.Once.PERSISTENT, diamonds.once());
        assertEquals(300, diamonds.debounceSeconds(), "vein debounce");

        assertEquals(EventSpec.Scope.WORLD, specs.get("thunderstorm").scope());
        assertEquals(EventSpec.Once.SESSION_PAIR, specs.get("fellowship").once());
        assertEquals(EventSpec.Path.PREDICTABLE, specs.get("first_nightfall").path());
    }
}
