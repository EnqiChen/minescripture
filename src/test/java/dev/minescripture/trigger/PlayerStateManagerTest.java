package dev.minescripture.trigger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStateManagerTest {

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void milestonesAndMutePersistAcrossRestarts(@TempDir Path tmp) {
        PlayerStateManager first = new PlayerStateManager(tmp);
        assertFalse(first.milestoneDone(P1, "found_diamonds"));
        first.markMilestone(P1, "found_diamonds");
        first.setMuted(P1, true);

        // "Restart": a fresh manager over the same data dir must see the flags.
        PlayerStateManager second = new PlayerStateManager(tmp);
        assertTrue(second.milestoneDone(P1, "found_diamonds"));
        assertTrue(second.isMuted(P1));
        assertFalse(second.milestoneDone(P1, "first_join"), "other milestones untouched");
    }
}
