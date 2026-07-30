package dev.minescripture.command;

import dev.minescripture.config.FallbackPool;
import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.present.MomentPresenter;
import dev.minescripture.scripture.PassageCache;
import dev.minescripture.scripture.ScriptureClient;
import dev.minescripture.trigger.AiBudgetGuard;
import dev.minescripture.trigger.StoryMemory;
import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Admin surface: /msc <explain|trigger|stats|demo|reload|prefetch>. */
public final class AdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS =
            List.of("explain", "trigger", "stats", "demo", "reset", "reload", "prefetch");

    private record DemoEvent(String key, String detail, long minutesAgo) {
    }

    /**
     * Deterministic demo scripts: scripted StoryMemory through the FULL real
     * pipeline. The player_death script is the same-event-different-story A/B
     * setup (death right after finding diamonds → perseverance, not comfort).
     */
    /** A named beat: the event to fire, the cause to report, and the story behind it. */
    private record Scenario(String label, String event, String cause, List<DemoEvent> story) {
    }

    /**
     * Beats for the camera. Each stages a story that steers Gloo toward a
     * particular register, then runs the real pipeline — the interpretation is
     * genuinely the model's, only the history is arranged.
     *
     * The three the footage needs to cover: a weighty verse, an encouraging one,
     * and a moment of humour.
     */
    private static final Map<String, Scenario> SCENARIOS = Map.of(
            // Weighty: a long session ending in repeated loss, nothing to show for it.
            "loss", new Scenario("a verse for loss", "player_death", "mob", List.of(
                    new DemoEvent("first_join", null, 95),
                    new DemoEvent("player_death", "mob", 41),
                    new DemoEvent("low_health_survival", "mob", 22),
                    new DemoEvent("player_death", "mob", 13))),
            // Encouraging: died moments after the best thing that happened all night.
            "perseverance", new Scenario("an encouraging verse", "player_death", "lava", List.of(
                    new DemoEvent("first_join", null, 52),
                    new DemoEvent("found_diamonds", "first diamonds", 3))),
            // Humour: a quiet, uneventful session and then a thoroughly silly death.
            "levity", new Scenario("a lighthearted moment", "player_death", "cactus", List.of(
                    new DemoEvent("first_join", null, 30),
                    new DemoEvent("eating_bread", null, 18),
                    new DemoEvent("sleep", null, 11))),
            // Wonder, for the diamond beat.
            "wonder", new Scenario("a verse of wonder", "found_diamonds", null, List.of(
                    new DemoEvent("first_join", null, 47),
                    new DemoEvent("player_death", "fall", 19))));

    private static final Map<String, List<DemoEvent>> DEMO_SCRIPTS = Map.of(
            "player_death", List.of(
                    new DemoEvent("found_diamonds", "first diamonds", 8),
                    new DemoEvent("low_health_survival", "skeleton", 5)),
            "found_diamonds", List.of(
                    new DemoEvent("first_join", null, 45),
                    new DemoEvent("player_death", "fall", 20)),
            "fellowship", List.of(
                    new DemoEvent("first_nightfall", null, 15)),
            "first_nightfall", List.of(
                    new DemoEvent("first_join", null, 10)));

    private static final List<String> EVENT_KEYS = List.of(
            "first_join", "first_nightfall", "survived_the_night", "sheltered_till_dawn",
            "eating_bread", "taming", "low_health_survival", "found_diamonds",
            "player_death", "sleep", "thunderstorm", "fellowship");

    private final Plugin plugin;
    private final MineScriptureConfig config;
    private final TriggerService service;
    private final MomentPresenter moments;
    private final AiBudgetGuard budget;
    private final FallbackPool pool;
    private final PassageCache passageCache;
    private final ScriptureClient scripture; // nullable

    public AdminCommand(Plugin plugin, MineScriptureConfig config, TriggerService service,
                        MomentPresenter moments, AiBudgetGuard budget, FallbackPool pool,
                        PassageCache passageCache, ScriptureClient scripture) {
        this.plugin = plugin;
        this.config = config;
        this.service = service;
        this.moments = moments;
        this.budget = budget;
        this.pool = pool;
        this.passageCache = passageCache;
        this.scripture = scripture;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "explain" -> explain(sender);
            case "trigger" -> trigger(sender, args, false);
            case "demo" -> trigger(sender, args, true);
            case "stats" -> stats(sender);
            case "reset" -> reset(sender);
            case "reload" -> reload(sender);
            case "prefetch" -> prefetch(sender);
            default -> sender.sendMessage(Component.text(
                    "/msc <explain|trigger|stats|demo|reset|reload|prefetch>", NamedTextColor.GRAY));
        }
        return true;
    }

    /** The judge panel — wording matches the architecture. */
    private void explain(CommandSender sender) {
        Player player = sender instanceof Player p ? p : null;
        MomentPresenter.ShownMoment last = player == null ? null : moments.lastShown(player.getUniqueId());
        if (last == null) {
            sender.sendMessage(Component.text("No moment to explain yet.", NamedTextColor.GRAY));
            return;
        }
        long minutes = service.story(last.ctx().playerId()).minutesPlayed(System.currentTimeMillis());
        String cause = last.ctx().cause() == null ? "" : " (cause: " + last.ctx().cause() + ")";
        sender.sendMessage(line("Moment: ", last.ctx().eventKey() + cause + ", " + minutes + " min into session."));
        if (last.isLevity()) {
            sender.sendMessage(line("Tone judged by Gloo AI Studio: ", "light — humor served instead of Scripture."));
            boolean fromGloo = last.quipSource() == MomentPresenter.QuipSource.GLOO;
            sender.sendMessage(line("Text: ", (fromGloo
                    ? "written by Gloo AI Studio, passed LevityGuard"
                    : "curated, human-written (Gloo's quip was unavailable or rejected)")
                    + " — never presented as Scripture."));
            return;
        }
        sender.sendMessage(line("Interpreted & recommended by Gloo AI Studio: ",
                last.interp().resonance() + " → " + last.interp().emotionalArc()
                        + " (emphasis: " + last.interp().emphasis() + ") → " + last.passage().ref()));
        sender.sendMessage(line("Verified by MineScripture: ",
                "canonical, retrievable, unseen this session. [source: " + last.origin() + "]"));
        sender.sendMessage(line("Text: ", "YouVersion (" + last.passage().translation() + ") — verbatim."));
    }

    private void trigger(CommandSender sender, String[] args, boolean demo) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only (needs a target).", NamedTextColor.GRAY));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /msc " + (demo ? "demo" : "trigger")
                    + " <event> [cause]", NamedTextColor.GRAY));
            return;
        }
        String event = args[1].toLowerCase(Locale.ROOT);
        String cause = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : defaultCause(event);
        // may be overridden by a named scenario below
        long now = System.currentTimeMillis();
        if (demo) {
            Scenario scenario = SCENARIOS.get(event);
            StoryMemory story = service.story(player.getUniqueId());
            List<DemoEvent> script = scenario != null ? scenario.story()
                    : DEMO_SCRIPTS.getOrDefault(event, List.of(new DemoEvent("first_join", null, 30)));
            for (DemoEvent scripted : script) {
                story.recordEvent(scripted.key(), scripted.detail(), now - scripted.minutesAgo() * 60_000L);
            }
            String firing = scenario != null ? scenario.event() : event;
            if (scenario != null && scenario.cause() != null) {
                cause = scenario.cause();
            }
            if ("player_death".equals(firing)) {
                story.markLostItems();
            }
            sender.sendMessage(line(scenario != null ? "Staging " + scenario.label() + ": " : "Staging: ",
                    script.size() + " prior moments → real pipeline, pacing stood down"));
            // Filming path: real story, real Gloo, real verification — but
            // re-shootable, and it won't burn a once-ever milestone.
            service.submitForFilming(new TriggerContext(player.getUniqueId(), firing, cause,
                    player.getWorld().getName(), null, now, Map.of()));
            return;
        }
        var decision = service.submit(new TriggerContext(player.getUniqueId(), event, cause,
                player.getWorld().getName(), null, now, Map.of()));
        if (decision.present()) {
            sender.sendMessage(line("Fired " + event + ": ",
                    decision.useAi() ? "asking Gloo for a fresh reading…" : "serving a curated verse ("
                            + decision.reason() + ")"));
        } else {
            sender.sendMessage(Component.text("Suppressed " + event + " — " + decision.reason()
                    + ("milestone_done".equals(decision.reason())
                    ? ". This is a once-ever moment; use /msc reset to arm it again." : "."),
                    NamedTextColor.RED));
        }
    }

    private String defaultCause(String event) {
        return switch (event) {
            case "player_death" -> "lava";
            case "low_health_survival" -> "mob";
            case "taming" -> "wolf";
            default -> null;
        };
    }

    private void stats(CommandSender sender) {
        long now = System.currentTimeMillis();
        sender.sendMessage(line("Fired: ", String.valueOf(service.firedSnapshot())));
        sender.sendMessage(line("Suppressed: ", String.valueOf(service.suppressedSnapshot())));
        sender.sendMessage(line("Sources: ", String.valueOf(service.originSnapshot())));
        sender.sendMessage(line("AI budget: ", budget.serverCallsLastHour(now) + "/"
                + config.budgetServerPerHour + " server calls this hour."));
        sender.sendMessage(line("Passage cache: ", passageCache.size() + " passages on disk/memory."));
        sender.sendMessage(line("Keys: ", "YouVersion " + (config.hasYouVersion() ? "OK" : "MISSING")
                + " · Gloo " + (config.hasGloo() ? "OK" : "MISSING")));
    }

    /** Re-arms the once-ever moments so they can be rehearsed and filmed again. */
    private void reset(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.GRAY));
            return;
        }
        service.resetMilestones(player.getUniqueId());
        sender.sendMessage(Component.text(
                "first_join, first_nightfall and found_diamonds can fire again for you.",
                NamedTextColor.GRAY));
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(Component.text(
                "config.yml re-read. Keys, budgets and cooldowns apply after a restart "
                        + "(hot-swap is a documented future improvement).", NamedTextColor.GRAY));
    }

    private void prefetch(CommandSender sender) {
        if (scripture == null) {
            sender.sendMessage(Component.text("No YouVersion key — cannot prefetch.", NamedTextColor.RED));
            return;
        }
        List<String> refs = List.copyOf(pool.verses().keySet());
        AtomicInteger done = new AtomicInteger();
        CompletableFuture<?>[] futures = refs.stream()
                .map(ref -> passageCache.getOrFetch(config.bibleId, ref, () -> scripture.fetch(ref))
                        .thenRun(done::incrementAndGet)
                        .exceptionally(err -> null))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).whenComplete((v, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text(
                        "Prefetched " + done.get() + "/" + refs.size() + " fallback passages.",
                        NamedTextColor.GRAY))));
        sender.sendMessage(Component.text("Prefetching " + refs.size() + " passages…", NamedTextColor.GRAY));
    }

    private Component line(String label, String value) {
        return Component.text()
                .append(Component.text(label, NamedTextColor.GOLD))
                .append(Component.text(value, NamedTextColor.WHITE))
                .build();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && "demo".equalsIgnoreCase(args[0])) {
            return java.util.stream.Stream.concat(SCENARIOS.keySet().stream(), EVENT_KEYS.stream())
                    .filter(s2 -> s2.startsWith(args[1].toLowerCase(Locale.ROOT))).sorted().toList();
        }
        if (args.length == 2 && "trigger".equalsIgnoreCase(args[0])) {
            return List.of("first_join", "first_nightfall", "survived_the_night", "sheltered_till_dawn",
                            "eating_bread",
                            "taming", "low_health_survival", "found_diamonds", "player_death",
                            "sleep", "thunderstorm", "fellowship").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
