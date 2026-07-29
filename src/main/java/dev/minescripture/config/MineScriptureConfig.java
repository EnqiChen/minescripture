package dev.minescripture.config;

import java.util.function.Function;

/**
 * Immutable runtime settings. Bukkit-free: the plugin adapts Bukkit's
 * FileConfiguration through {@link ConfigView}; tests build instances directly.
 * API keys resolve config-first, then environment (MSC_YVP_KEY / MSC_GLOO_ID /
 * MSC_GLOO_SECRET) — empty config values defer to env.
 */
public final class MineScriptureConfig {

    /** Minimal read view over whatever backs the config (FileConfiguration in the plugin). */
    public interface ConfigView {
        String str(String path, String def);

        int integer(String path, int def);

        boolean bool(String path, boolean def);

        double dbl(String path, double def);
    }

    public final String yvpKey;
    public final int bibleId;
    public final String glooClientId;
    public final String glooClientSecret;
    public final String tradition;
    public final double temperature;
    public final long timeoutSuddenMs;
    public final long timeoutBackgroundMs;
    public final boolean levity;
    public final boolean levityAi;
    public final int budgetPlayerPerHour;
    public final int budgetServerPerHour;
    public final int globalCooldownSeconds;
    public final int globalPriorityCooldownSeconds;
    public final int sessionCap;
    public final int levityCooldownSeconds;
    public final double deathEscalationFactor;
    public final int deathMaxCooldownSeconds;
    public final int deathClusterResetSeconds;
    public final boolean chime;

    private MineScriptureConfig(Builder b) {
        this.yvpKey = b.yvpKey;
        this.bibleId = b.bibleId;
        this.glooClientId = b.glooClientId;
        this.glooClientSecret = b.glooClientSecret;
        this.tradition = b.tradition;
        this.temperature = b.temperature;
        this.timeoutSuddenMs = b.timeoutSuddenMs;
        this.timeoutBackgroundMs = b.timeoutBackgroundMs;
        this.levity = b.levity;
        this.levityAi = b.levityAi;
        this.budgetPlayerPerHour = b.budgetPlayerPerHour;
        this.budgetServerPerHour = b.budgetServerPerHour;
        this.globalCooldownSeconds = b.globalCooldownSeconds;
        this.globalPriorityCooldownSeconds = b.globalPriorityCooldownSeconds;
        this.sessionCap = b.sessionCap;
        this.levityCooldownSeconds = b.levityCooldownSeconds;
        this.deathEscalationFactor = b.deathEscalationFactor;
        this.deathMaxCooldownSeconds = b.deathMaxCooldownSeconds;
        this.deathClusterResetSeconds = b.deathClusterResetSeconds;
        this.chime = b.chime;
    }

    public boolean hasYouVersion() {
        return !yvpKey.isBlank();
    }

    public boolean hasGloo() {
        return !glooClientId.isBlank() && !glooClientSecret.isBlank();
    }

    public static MineScriptureConfig from(ConfigView v, Function<String, String> env) {
        Builder b = new Builder();
        b.yvpKey = firstNonBlank(v.str("youversion.app_key", ""), envOr(env, "MSC_YVP_KEY"));
        b.bibleId = v.integer("youversion.bible_id", 3034);
        b.glooClientId = firstNonBlank(v.str("gloo.client_id", ""), envOr(env, "MSC_GLOO_ID"));
        b.glooClientSecret = firstNonBlank(v.str("gloo.client_secret", ""), envOr(env, "MSC_GLOO_SECRET"));
        b.tradition = v.str("gloo.tradition", "broad_christian");
        b.temperature = v.dbl("gloo.temperature", 0.4);
        b.timeoutSuddenMs = v.integer("gloo.timeout_ms_sudden", 9000);
        b.timeoutBackgroundMs = v.integer("gloo.timeout_ms_background", 12000);
        b.levity = v.bool("levity", true);
        b.levityAi = v.bool("levity_ai", false);
        b.budgetPlayerPerHour = v.integer("budget.player_per_hour", 10);
        b.budgetServerPerHour = v.integer("budget.server_per_hour", 100);
        b.globalCooldownSeconds = v.integer("cooldowns.global_s", 90);
        b.globalPriorityCooldownSeconds = v.integer("cooldowns.global_priority_s", 15);
        b.sessionCap = v.integer("cooldowns.session_cap", 12);
        b.levityCooldownSeconds = v.integer("cooldowns.levity_s", 600);
        b.deathEscalationFactor = v.dbl("deaths.escalation_factor", 1.75);
        b.deathMaxCooldownSeconds = v.integer("deaths.max_cooldown_s", 300);
        b.deathClusterResetSeconds = v.integer("deaths.run_reset_s", 600);
        b.chime = v.bool("presentation.chime", true);
        return new MineScriptureConfig(b);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String envOr(Function<String, String> env, String key) {
        String val = env.apply(key);
        return val == null ? "" : val;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    public static final class Builder {
        private String yvpKey = "";
        private int bibleId = 3034;
        private String glooClientId = "";
        private String glooClientSecret = "";
        private String tradition = "broad_christian";
        private double temperature = 0.4;
        private long timeoutSuddenMs = 9000;
        private long timeoutBackgroundMs = 12000;
        private boolean levity = true;
        private boolean levityAi = false;
        private int budgetPlayerPerHour = 10;
        private int budgetServerPerHour = 100;
        private int globalCooldownSeconds = 90;
        private int globalPriorityCooldownSeconds = 15;
        private int sessionCap = 12;
        private int levityCooldownSeconds = 600;
        private double deathEscalationFactor = 1.75;
        private int deathMaxCooldownSeconds = 300;
        private int deathClusterResetSeconds = 600;
        private boolean chime = true;

        public Builder yvpKey(String v) { this.yvpKey = v; return this; }
        public Builder bibleId(int v) { this.bibleId = v; return this; }
        public Builder glooClientId(String v) { this.glooClientId = v; return this; }
        public Builder glooClientSecret(String v) { this.glooClientSecret = v; return this; }
        public Builder tradition(String v) { this.tradition = v; return this; }
        public Builder temperature(double v) { this.temperature = v; return this; }
        public Builder timeoutSuddenMs(long v) { this.timeoutSuddenMs = v; return this; }
        public Builder timeoutBackgroundMs(long v) { this.timeoutBackgroundMs = v; return this; }
        public Builder levity(boolean v) { this.levity = v; return this; }
        public Builder levityAi(boolean v) { this.levityAi = v; return this; }
        public Builder budgetPlayerPerHour(int v) { this.budgetPlayerPerHour = v; return this; }
        public Builder budgetServerPerHour(int v) { this.budgetServerPerHour = v; return this; }
        public Builder globalCooldownSeconds(int v) { this.globalCooldownSeconds = v; return this; }
        public Builder globalPriorityCooldownSeconds(int v) { this.globalPriorityCooldownSeconds = v; return this; }
        public Builder sessionCap(int v) { this.sessionCap = v; return this; }
        public Builder levityCooldownSeconds(int v) { this.levityCooldownSeconds = v; return this; }
        public Builder deathEscalationFactor(double v) { this.deathEscalationFactor = v; return this; }
        public Builder deathMaxCooldownSeconds(int v) { this.deathMaxCooldownSeconds = v; return this; }
        public Builder deathClusterResetSeconds(int v) { this.deathClusterResetSeconds = v; return this; }
        public Builder chime(boolean v) { this.chime = v; return this; }

        public MineScriptureConfig build() {
            return new MineScriptureConfig(this);
        }
    }
}
