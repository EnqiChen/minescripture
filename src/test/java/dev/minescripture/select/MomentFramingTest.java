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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gameplay facts alone under-describe some moments. A first arrival has no
 * session story by definition, so a model reading only the event name is
 * guessing: asked to interpret "first_join" it recommended Genesis 1:1 — a fine
 * verse for a newly generated world, and no help at all to a greeting written to
 * be explained by a verse about sojourning.
 *
 * The fix is to tell it what the moment means, not to take the choice away.
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
    void theWelcomeTellsGlooWhatItIsTryingToSay() {
        FallbackPool pool = pool();
        String note = pool.aiNoteFor("first_join");
        assertNotNull(note, "the greeting must explain its own intent to the model");
        assertTrue(note.contains("Sojourner"), "it names the word the verse has to justify");

        String message = interpreter(pool).buildUserMessage(
                TriggerContext.of(P1, "first_join", 0L), new StoryMemory(0L));
        assertTrue(message.contains("what_this_moment_means"), message);
        assertTrue(message.contains("sojourner"), "the framing reaches the model: " + message);
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
                TriggerContext.of(P1, "first_join", 0L), new StoryMemory(0L));
        assertFalse(message.contains("what_this_moment_means"));
    }
}
