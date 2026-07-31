package dev.minescripture;

import dev.minescripture.command.AdminCommand;
import dev.minescripture.command.VerseCommand;
import dev.minescripture.config.EventSpecs;
import dev.minescripture.config.FallbackPool;
import dev.minescripture.config.HumorPool;
import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.event.DeathListener;
import dev.minescripture.event.FellowshipTracker;
import dev.minescripture.event.LifecycleListener;
import dev.minescripture.event.MilestoneListener;
import dev.minescripture.event.DayCycleClock;
import dev.minescripture.event.SurvivalListener;
import dev.minescripture.event.WorldListener;
import dev.minescripture.journal.SessionJournal;
import dev.minescripture.present.MomentPresenter;
import dev.minescripture.present.Presenter;
import dev.minescripture.scripture.Abridger;
import dev.minescripture.scripture.Passage;
import dev.minescripture.scripture.PassageCache;
import dev.minescripture.scripture.ScriptureClient;
import dev.minescripture.select.GlooClient;
import dev.minescripture.select.GlooTokenManager;
import dev.minescripture.select.MomentInterpreter;
import dev.minescripture.select.MomentProfileCache;
import dev.minescripture.select.RefValidator;
import dev.minescripture.select.SessionVerseMemory;
import dev.minescripture.trigger.AiBudgetGuard;
import dev.minescripture.trigger.PlayerStateManager;
import dev.minescripture.trigger.TriggerPolicy;
import dev.minescripture.trigger.TriggerService;
import dev.minescripture.util.Http;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MineScripture — an AI narrative layer that recognizes meaningful moments and
 * connects them with Scripture. Minecraft detects the event; Gloo understands
 * the moment and recommends Scripture; this plugin controls the gate;
 * YouVersion provides every word of Scripture the player reads.
 */
public final class MineScripturePlugin extends JavaPlugin {

    private TriggerService service;
    private PlayerStateManager playerState;

    /** Bump whenever a shipped default VALUE changes; see config.yml. */
    private static final int CONFIG_VERSION = 3;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateStaleConfig();
        MineScriptureConfig config = loadSettings();
        EventSpecs specs = EventSpecs.load(resource("events.json"));
        FallbackPool pool = FallbackPool.load(resource("fallback.json"));
        HumorPool humor = HumorPool.load(resource("humor.json"));

        Path dataDir = getDataFolder().toPath();
        playerState = new PlayerStateManager(dataDir);
        PassageCache passageCache = new PassageCache(dataDir);
        Http http = new Http();

        ScriptureClient scripture = null;
        if (config.hasYouVersion()) {
            scripture = new ScriptureClient(http, ScriptureClient.DEFAULT_BASE,
                    config.yvpKey, config.bibleId, 8_000);
        } else {
            getLogger().warning("YouVersion key missing (MSC_YVP_KEY) — serving cached/disk passages only.");
        }

        TriggerService.InterpretationSource interpreter = null;
        GlooClient gloo = null;
        if (config.hasGloo()) {
            GlooTokenManager tokens = new GlooTokenManager(http, GlooTokenManager.DEFAULT_BASE,
                    config.glooClientId, config.glooClientSecret);
            gloo = new GlooClient(http, GlooClient.DEFAULT_BASE, tokens, config,
                    msg -> getLogger().info(msg));
            interpreter = new MomentInterpreter(gloo, config, msg -> getLogger().info(msg));
        } else {
            getLogger().warning("Gloo credentials missing (MSC_GLOO_ID / MSC_GLOO_SECRET) — "
                    + "curated default interpretations only.");
        }

        AiBudgetGuard budget = new AiBudgetGuard(config.budgetPlayerPerHour, config.budgetServerPerHour);
        TriggerPolicy policy = new TriggerPolicy(specs, config, budget);
        RefValidator validator = new RefValidator(pool, scripture != null
                ? scripture.existenceChecker(passageCache)
                : ref -> CompletableFuture.completedFuture(Optional.empty()));
        SessionVerseMemory verseMemory = new SessionVerseMemory();
        SessionJournal journal = new SessionJournal();
        Presenter presenter = new Presenter(config);
        MomentPresenter moments = new MomentPresenter(this, config, pool, humor, policy, validator,
                verseMemory, passageCache, scripture, presenter, journal, playerState, specs);

        service = new TriggerService(config, specs, policy, pool, new MomentProfileCache(),
                playerState, interpreter, moments, task -> {
            if (isEnabled()) {
                Bukkit.getScheduler().runTask(this, task);
            }
        }, msg -> getLogger().info(msg));
        moments.bindStories(service::story);

        PluginManager pm = getServer().getPluginManager();
        DayCycleClock dayCycle = new DayCycleClock(service, playerState);
        pm.registerEvents(new LifecycleListener(this, service, journal, moments, dataDir, playerState), this);
        pm.registerEvents(new DeathListener(service, moments, dayCycle), this);
        pm.registerEvents(new SurvivalListener(service, dayCycle, this), this);
        pm.registerEvents(new WorldListener(service), this);
        pm.registerEvents(new MilestoneListener(service), this);
        Bukkit.getScheduler().runTaskTimer(this, dayCycle, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, new FellowshipTracker(service), 200L, 200L);

        Objects.requireNonNull(getCommand("verse"))
                .setExecutor(new VerseCommand(config, moments, playerState, journal));
        Objects.requireNonNull(getCommand("msc"))
                .setExecutor(new AdminCommand(this, config, service, moments, budget, pool,
                        passageCache, scripture));

        if (scripture != null) {
            warmCache(config, pool, passageCache, scripture, gloo);
        }
        getLogger().info("MineScripture enabled — AI understands the moment. "
                + "Scripture provides the meaning.");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (playerState != null) {
            playerState.save();
        }
    }

    /** Startup warmer: fallback refs onto disk so outages can never break a moment. */
    private void warmCache(MineScriptureConfig config, FallbackPool pool,
                           PassageCache cache, ScriptureClient scripture, GlooClient gloo) {
        List<String> refs = List.copyOf(pool.verses().keySet());
        AtomicInteger ok = new AtomicInteger();
        CompletableFuture<?>[] futures = refs.stream()
                .map(ref -> cache.getOrFetch(config.bibleId, ref, () -> scripture.fetch(ref))
                        .thenRun(ok::incrementAndGet)
                        .exceptionally(err -> null))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).whenComplete((v, err) -> {
            getLogger().info("Fallback pool warm: " + ok.get() + "/" + refs.size()
                    + " passages cached.");
            preAbridge(config, cache, gloo);
        });
    }

    /**
     * Shortens the long passages ahead of time. Deliberately at startup rather
     * than at showtime: an abridgement needs its own Gloo call, and the gap
     * between a moment and its verse has no room for a second one.
     */
    private void preAbridge(MineScriptureConfig config, PassageCache cache, GlooClient gloo) {
        if (gloo == null || config.abridgeOverChars <= 0) {
            return;
        }
        Abridger abridger = new Abridger(
                prompt -> gloo.complete("You shorten Bible verses by deleting words only.", prompt)
                        .thenApply(GlooClient.Completion::content),
                config.abridgeOverChars);
        List<Passage> tooLong = cache.longerThan(config.bibleId, config.abridgeOverChars);
        if (tooLong.isEmpty()) {
            return;
        }
        AtomicInteger done = new AtomicInteger();
        CompletableFuture<?>[] jobs = tooLong.stream()
                .map(p -> abridger.abridge(p).thenAccept(result -> {
                    if (result.isAbridged()) {
                        cache.put(config.bibleId, result);
                        done.incrementAndGet();
                    }
                }).exceptionally(err -> null))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(jobs).whenComplete((v, err) ->
                getLogger().info("Shortened " + done.get() + "/" + tooLong.size()
                        + " long passages for readability (whole verse still on /verse)."));
    }

    /**
     * Bukkit's saveDefaultConfig() does nothing when the file already exists, so
     * a change to a shipped default never reaches an existing install. That is
     * not a theoretical problem: a stale timeout sat on a test server overriding
     * a fix, and from in-game it looked exactly like the fix hadn't worked.
     * An out-of-date file is therefore backed up and replaced, loudly.
     */
    private void migrateStaleConfig() {
        int onDisk = getConfig().getInt("config_version", 1);
        if (onDisk >= CONFIG_VERSION) {
            return;
        }
        Path dir = getDataFolder().toPath();
        Path backup = dir.resolve("config.v" + onDisk + ".bak.yml");
        try {
            Files.copy(dir.resolve("config.yml"), backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().warning("Could not back up the old config.yml: " + e.getMessage());
        }
        saveResource("config.yml", true);
        reloadConfig();
        getLogger().warning("config.yml was version " + onDisk + ", this build ships version "
                + CONFIG_VERSION + ". It has been replaced with current defaults; your old file is "
                + backup.getFileName() + ". Re-apply any customisations you had.");
    }

    private MineScriptureConfig loadSettings() {
        FileConfiguration fc = getConfig();
        return MineScriptureConfig.from(new MineScriptureConfig.ConfigView() {
            @Override
            public String str(String path, String def) {
                return fc.getString(path, def);
            }

            @Override
            public int integer(String path, int def) {
                return fc.getInt(path, def);
            }

            @Override
            public boolean bool(String path, boolean def) {
                return fc.getBoolean(path, def);
            }

            @Override
            public double dbl(String path, double def) {
                return fc.getDouble(path, def);
            }
        }, System::getenv);
    }

    private Reader resource(String name) {
        return new InputStreamReader(Objects.requireNonNull(getResource(name),
                "bundled resource missing: " + name), StandardCharsets.UTF_8);
    }
}
