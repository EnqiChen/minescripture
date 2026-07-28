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
import dev.minescripture.event.NightfallClock;
import dev.minescripture.event.SurvivalListener;
import dev.minescripture.event.WorldListener;
import dev.minescripture.journal.SessionJournal;
import dev.minescripture.present.MomentPresenter;
import dev.minescripture.present.Presenter;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
        if (config.hasGloo()) {
            GlooTokenManager tokens = new GlooTokenManager(http, GlooTokenManager.DEFAULT_BASE,
                    config.glooClientId, config.glooClientSecret);
            GlooClient gloo = new GlooClient(http, GlooClient.DEFAULT_BASE, tokens, config,
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
                verseMemory, passageCache, scripture, presenter, journal);

        service = new TriggerService(config, specs, policy, pool, new MomentProfileCache(),
                playerState, interpreter, moments, task -> {
            if (isEnabled()) {
                Bukkit.getScheduler().runTask(this, task);
            }
        });
        moments.bindStories(service::story);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new LifecycleListener(this, service, journal, moments, dataDir), this);
        pm.registerEvents(new DeathListener(service, moments), this);
        pm.registerEvents(new SurvivalListener(service), this);
        pm.registerEvents(new WorldListener(service), this);
        pm.registerEvents(new MilestoneListener(service), this);
        Bukkit.getScheduler().runTaskTimer(this, new NightfallClock(service, playerState), 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, new FellowshipTracker(service), 200L, 200L);

        Objects.requireNonNull(getCommand("verse"))
                .setExecutor(new VerseCommand(config, moments, playerState, journal));
        Objects.requireNonNull(getCommand("msc"))
                .setExecutor(new AdminCommand(this, config, service, moments, budget, pool,
                        passageCache, scripture));

        if (scripture != null) {
            warmCache(config, pool, passageCache, scripture);
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
                           PassageCache cache, ScriptureClient scripture) {
        List<String> refs = List.copyOf(pool.verses().keySet());
        AtomicInteger ok = new AtomicInteger();
        CompletableFuture<?>[] futures = refs.stream()
                .map(ref -> cache.getOrFetch(config.bibleId, ref, () -> scripture.fetch(ref))
                        .thenRun(ok::incrementAndGet)
                        .exceptionally(err -> null))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).whenComplete((v, err) ->
                getLogger().info("Fallback pool warm: " + ok.get() + "/" + refs.size()
                        + " passages cached."));
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
