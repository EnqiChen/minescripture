package dev.minescripture.config;

/** One event's structural spec, loaded from events.json. */
public record EventSpec(
        String key,
        Path path,
        Tier tier,
        boolean priority,
        int cooldownSeconds,
        int debounceSeconds,
        Once once,
        Scope scope,
        boolean levityEligible
) {
    /** Two-path timing: predictable = cache-first async; sudden = fresh bounded interpretation. */
    public enum Path { PREDICTABLE, SUDDEN }

    /** Drives Presenter format: actionbar / chat block / title + chat block. */
    public enum Tier { MINOR, STANDARD, MAJOR }

    /** Fire-once semantics. */
    public enum Once { NONE, PERSISTENT, SESSION_PAIR }

    /** Cooldown scope. */
    public enum Scope { PLAYER, WORLD }
}
