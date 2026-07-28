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
            List.of("explain", "trigger", "stats", "demo", "reload", "prefetch");

    private record DemoEvent(String key, String detail, long minutesAgo) {
    }

    /**
     * Deterministic demo scripts: scripted StoryMemory through the FULL real
     * pipeline. The player_death script is the same-event-different-story A/B
     * setup (death right after finding diamonds → perseverance, not comfort).
     */
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
            case "reload" -> reload(sender);
            case "prefetch" -> prefetch(sender);
            default -> sender.sendMessage(Component.text(
                    "/msc <explain|trigger|stats|demo|reload|prefetch>", NamedTextColor.GRAY));
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
            sender.sendMessage(line("Text: ", (config.levityAi ? "AI-generated quip (LevityGuard-approved)"
                    : "curated, human-written") + " — never presented as Scripture."));
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
        long now = System.currentTimeMillis();
        if (demo) {
            StoryMemory story = service.story(player.getUniqueId());
            for (DemoEvent scripted : DEMO_SCRIPTS.getOrDefault(event,
                    List.of(new DemoEvent("first_join", null, 30)))) {
                story.recordEvent(scripted.key(), scripted.detail(), now - scripted.minutesAgo() * 60_000L);
            }
            if ("player_death".equals(event)) {
                story.markLostItems();
            }
            sender.sendMessage(Component.text("Demo story staged → running the real pipeline.",
                    NamedTextColor.GRAY));
        }
        service.submit(new TriggerContext(player.getUniqueId(), event, cause,
                player.getWorld().getName(), null, now, Map.of()));
        sender.sendMessage(Component.text("Submitted " + event
                + (cause != null ? " (" + cause + ")" : "") + ".", NamedTextColor.GRAY));
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
        if (args.length == 2 && ("trigger".equalsIgnoreCase(args[0]) || "demo".equalsIgnoreCase(args[0]))) {
            return List.of("first_join", "first_nightfall", "survived_the_night", "eating_bread",
                            "taming", "low_health_survival", "found_diamonds", "player_death",
                            "sleep", "thunderstorm", "fellowship").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
