package dev.minescripture.select;

import dev.minescripture.config.FallbackPool;
import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.trigger.StoryMemory;
import dev.minescripture.trigger.TriggerContext;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gameplay facts alone under-describe some moments. A returning player's
 * greeting speaks of a road taken up again, and the event name "rejoin" carries
 * none of that, so the moment says what it means alongside the facts.
 *
 * The framing steers the reading; it never picks the verse. Where we do want a
 * fixed verse — the first arrival, whose greeting has a word to justify — the
 * moment is scripted outright and the model is not asked at all.
 */
class MomentFramingTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    private static FallbackPool pool() {
        return FallbackPool.load(new InputStreamReader(
                MomentFramingTest.class.getResourceAsStream("/fallback.json"),
                StandardCharsets.UTF_8));
    }

    private static MomentInterpreter interpreter(FallbackPool pool) {
        return new MomentInterpreter(null, MineScriptureConfig.builder().build(),
                msg -> {
                }, pool::aiNoteFor);
    }

    @Test
    void aReturningPlayersGreetingTellsGlooWhatItIsSaying() {
        FallbackPool pool = pool();
        String note = pool.aiNoteFor("rejoin");
        assertNotNull(note, "the greeting must explain its own intent to the model");
        assertTrue(note.contains("sojourner"), "it names the word the verse has to sit beside");

        String message = interpreter(pool).buildUserMessage(
                TriggerContext.of(P1, "rejoin", 0L), new StoryMemory(0L));
        assertTrue(message.contains("what_this_moment_means"), message);
        assertTrue(message.contains("pilgrimage"), "the framing reaches the model: " + message);
    }

    /**
     * first_join is scripted, so nothing is ever sent for it. A framing note left
     * on an event that never calls the model would be dead data that reads like a
     * live instruction — the next person to change the greeting would edit it and
     * wonder why nothing happened.
     */
    @Test
    void theScriptedWelcomeCarriesNoFramingBecauseNothingReadsIt() {
        FallbackPool pool = pool();
        assertNull(pool.aiNoteFor("first_join"));
        String message = interpreter(pool).buildUserMessage(
                TriggerContext.of(P1, "first_join", 0L), new StoryMemory(0L));
        assertFalse(message.contains("what_this_moment_means"), message);
    }

    @Test
    void momentsWithNothingToExplainSendNoFraming() {
        // Most events are fully described by what happened. Padding every call
        // with prose would dilute the facts the model actually reasons from.
        String message = interpreter(pool()).buildUserMessage(
                TriggerContext.of(P1, "player_death", 0L), new StoryMemory(0L));
        assertFalse(message.contains("what_this_moment_means"), message);
        assertTrue(message.contains("player_death"));
    }

    @Test
    void framingIsOptionalSoTheInterpreterWorksWithoutAPool() {
        MomentInterpreter bare = new MomentInterpreter(null,
                MineScriptureConfig.builder().build(), msg -> {
                });
        String message = bare.buildUserMessage(
                TriggerContext.of(P1, "rejoin", 0L), new StoryMemory(0L));
        assertFalse(message.contains("what_this_moment_means"));
    }
}
