package dev.minescripture.select;

import org.junit.jupiter.api.Test;

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
}
