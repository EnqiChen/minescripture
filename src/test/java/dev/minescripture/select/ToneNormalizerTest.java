package dev.minescripture.select;

import dev.minescripture.config.EventSpecs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A live Gloo call returned "supportive_resilience" — not one of the six tones
 * we present. A strict check flattened it to "solemn", which also made Levity
 * unreachable, since humour needs exactly "light".
 */
class ToneNormalizerTest {

    @Test
    void exactTonesPassThrough() {
        for (String t : new String[]{"solemn", "warm", "awe", "calm", "encouraging", "light"}) {
            assertEquals(t, MomentInterpreter.normalizeTone(t));
            assertEquals(t, MomentInterpreter.normalizeTone(t.toUpperCase()));
        }
    }

    @Test
    void theRealWorldResponseThatBrokeItMapsSensibly() {
        assertEquals("encouraging", MomentInterpreter.normalizeTone("supportive_resilience"));
    }

    @Test
    void inventedComedicTonesStillReachLevity() {
        assertEquals("light", MomentInterpreter.normalizeTone("humorous"));
        assertEquals("light", MomentInterpreter.normalizeTone("playful_irony"));
        assertEquals("light", MomentInterpreter.normalizeTone("gently_comic"));
        assertEquals("light", MomentInterpreter.normalizeTone("wry_amusement"));
    }

    @Test
    void otherInventedTonesLandOnTheNearestPresentableOne() {
        assertEquals("awe", MomentInterpreter.normalizeTone("reverent_wonder"));
        assertEquals("calm", MomentInterpreter.normalizeTone("peaceful_stillness"));
        assertEquals("warm", MomentInterpreter.normalizeTone("tender_gratitude"));
        assertEquals("encouraging", MomentInterpreter.normalizeTone("hopeful_perseverance"));
        assertEquals("solemn", MomentInterpreter.normalizeTone("utterly_unmappable_nonsense"));
        assertEquals("solemn", MomentInterpreter.normalizeTone(null));
    }

    /**
     * events.json marked low_health_survival levity-eligible while the prompt told
     * Gloo that "danger survived" is never light. The gate stood open and the model
     * was instructed never to walk through it, so four near-misses in a row came
     * back "encouraging" and the eligibility flag did nothing at all. Every prompt
     * rule in this project has a code-side twin; this is the case where the two
     * disagreed and only the logs could tell.
     */
    @Test
    void thePromptDoesNotForbidLevityWhereTheSpecsAllowIt() {
        EventSpecs specs = EventSpecs.load(new java.io.InputStreamReader(
                ToneNormalizerTest.class.getResourceAsStream("/events.json"),
                java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(specs.get("low_health_survival").levityEligible(),
                "near-death is one of the two mishap-shaped events");
        assertFalse(MomentInterpreter.SYSTEM_PROMPT.contains("danger survived"),
                "the prompt must not rule out levity for an event events.json allows it for");
    }
}
