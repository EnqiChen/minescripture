package dev.minescripture.present;

import dev.minescripture.config.FallbackPool;
import dev.minescripture.config.HumorPool;
import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.journal.SessionJournal;
import dev.minescripture.scripture.Passage;
import dev.minescripture.scripture.PassageCache;
import dev.minescripture.scripture.ScriptureClient;
import dev.minescripture.select.CandidateScorer;
import dev.minescripture.select.Interpretation;
import dev.minescripture.select.LevityGuard;
import dev.minescripture.select.RefValidator;
import dev.minescripture.select.SessionVerseMemory;
import dev.minescripture.trigger.StoryMemory;
import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerPolicy;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The MomentSink: takes the gate's decision + Gloo's (or the fallback's)
 * interpretation and turns it into something on screen.
 *
 * Responsibilities: levity routing (tone gate + LevityGuard + curated pool),
 * ref validation → candidate scoring → verbatim YouVersion fetch (cache-first,
 * never-fail chain), death-screen pending (verse presents AT RESPAWN), the
 * one-beat delay on diamonds, world/pair broadcast recipients, journal and
 * anti-repeat bookkeeping, and the /verse | /msc explain replay store.
 */
public final class MomentPresenter implements TriggerService.MomentSink {

    public record ShownMoment(TriggerContext ctx, Interpretation interp, TriggerService.Origin origin,
                              Passage passage, String quip, String frame, long at) {
        public boolean isLevity() {
            return quip != null;
        }
    }

    private final Plugin plugin;
    private final Logger log;
    private final MineScriptureConfig config;
    private final FallbackPool pool;
    private final HumorPool humor;
    private final TriggerPolicy policy;
    private final RefValidator validator;
    private final SessionVerseMemory verseMemory;
    private final PassageCache passageCache;
    private final ScriptureClient scripture; // nullable: no YVP key → cache-only mode
    private final Presenter presenter;
    private final SessionJournal journal;
    private final Random random = new Random();

    private Function<UUID, StoryMemory> stories = id -> null; // bound after TriggerService exists

    private final Map<UUID, Runnable> pendingRespawn = new ConcurrentHashMap<>();
    private final Map<UUID, ShownMoment> lastShown = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> recentQuips = new ConcurrentHashMap<>();

    public MomentPresenter(Plugin plugin, MineScriptureConfig config, FallbackPool pool, HumorPool humor,
                           TriggerPolicy policy, RefValidator validator, SessionVerseMemory verseMemory,
                           PassageCache passageCache, ScriptureClient scripture, Presenter presenter,
                           SessionJournal journal) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.config = config;
        this.pool = pool;
        this.humor = humor;
        this.policy = policy;
        this.validator = validator;
        this.verseMemory = verseMemory;
        this.passageCache = passageCache;
        this.scripture = scripture;
        this.presenter = presenter;
        this.journal = journal;
    }

    /** Called once by the plugin after TriggerService is constructed. */
    public void bindStories(Function<UUID, StoryMemory> stories) {
        this.stories = stories;
    }

    // ------------------------------------------------------------------ sink

    @Override
    public void present(TriggerContext ctx, Interpretation interp, TriggerService.Origin origin) {
        Player player = Bukkit.getPlayer(ctx.playerId());
        if (player == null) {
            return;
        }
        // Levity branch: Gloo judged this moment comedic. Humor replaces the
        // verse for THIS moment only; it never fires from curated defaults
        // (they are never tone-light) and never on meaningful moments.
        if (interp.isLight() && config.levity && policy.levityAllowed(ctx.playerId(), ctx.at())) {
            String quip = chooseQuip(ctx, interp);
            if (quip != null) {
                policy.markLevity(ctx.playerId(), ctx.at());
                ShownMoment moment = new ShownMoment(ctx, interp, origin, null, quip, null, ctx.at());
                lastShown.put(ctx.playerId(), moment);
                deliverTimed(ctx, player, p -> presenter.showLevity(p, quip));
                return;
            }
        }
        selectVerse(ctx, interp).thenAccept(passage -> {
            if (passage.isEmpty()) {
                log.warning("[" + ctx.eventKey() + "] no passage available (no key/cache?) — moment skipped");
                return;
            }
            String frame = frameFor(ctx.eventKey());
            ShownMoment moment = new ShownMoment(ctx, interp, origin, passage.get(), null, frame, ctx.at());
            deliverVerse(ctx, moment);
        }).exceptionally(err -> {
            log.warning("[" + ctx.eventKey() + "] presentation failed: " + err.getMessage());
            return null;
        });
    }

    // ---------------------------------------------------------- verse branch

    /** Gloo refs → normalize/validate → score → fetch verbatim text; never-fail chain. */
    private CompletableFuture<Optional<Passage>> selectVerse(TriggerContext ctx, Interpretation interp) {
        Set<String> seenToday = verseMemory.seenToday(ctx.playerId(), ctx.at());
        return validator.filterValid(interp.recommendedRefs()).thenCompose(valid -> {
            String picked = CandidateScorer.pickBest(valid, interp, ctx.eventKey(), pool,
                            seenToday, seenToday, validator.validatedRefs())
                    .orElseGet(() -> pool.pickFallbackRef(ctx.eventKey(), seenToday));
            List<String> chain = fetchChain(picked, ctx.eventKey(), seenToday);
            return fetchFirstAvailable(chain, 0);
        });
    }

    /** Picked ref first, then the event's curated defaults — display never blocks. */
    private List<String> fetchChain(String picked, String eventKey, Set<String> seen) {
        Set<String> chain = new LinkedHashSet<>();
        if (picked != null) {
            chain.add(picked);
        }
        FallbackPool.EventDefault defaults = pool.defaultsFor(eventKey);
        if (defaults != null) {
            String unseenFirst = pool.pickFallbackRef(eventKey, seen);
            if (unseenFirst != null) {
                chain.add(unseenFirst);
            }
            chain.addAll(defaults.refs());
        }
        return new ArrayList<>(chain);
    }

    private CompletableFuture<Optional<Passage>> fetchFirstAvailable(List<String> refs, int index) {
        if (index >= refs.size()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String ref = refs.get(index);
        if (scripture == null) {
            // Cache-only mode (no YVP key): serve what the disk knows.
            Optional<Passage> cached = passageCache.get(config.bibleId, ref);
            return cached.isPresent()
                    ? CompletableFuture.completedFuture(cached)
                    : fetchFirstAvailable(refs, index + 1);
        }
        return passageCache.getOrFetch(config.bibleId, ref, () -> scripture.fetch(ref))
                .thenApply(Optional::of)
                .exceptionally(err -> Optional.empty())
                .thenCompose(passage -> passage.isPresent()
                        ? CompletableFuture.completedFuture(passage)
                        : fetchFirstAvailable(refs, index + 1));
    }

    private void deliverVerse(TriggerContext ctx, ShownMoment moment) {
        deliverTimed(ctx, Bukkit.getPlayer(ctx.playerId()), anchor -> {
            for (Player recipient : recipients(ctx)) {
                show(recipient, ctx, moment);
                markSeen(recipient.getUniqueId(), moment);
            }
            lastShown.put(ctx.playerId(), moment);
        });
    }

    private void show(Player player, TriggerContext ctx, ShownMoment moment) {
        switch (tierOf(ctx.eventKey())) {
            case "minor" -> presenter.showMinor(player, moment.passage());
            case "major" -> presenter.showMajor(player, moment.frame(), moment.passage());
            default -> presenter.showStandard(player, moment.frame(), moment.passage());
        }
        journal.add(player.getUniqueId(), ctx.eventKey(), moment.passage());
    }

    // ------------------------------------------------------------ timing

    /**
     * Timing to the moment: a dead player's verse waits for the respawn screen
     * to clear ("You awaken at dawn."); diamonds get a deliberate one-beat
     * delay that reads as reverence, not lag; everything else shows now.
     * Always lands back on the main thread.
     */
    private void deliverTimed(TriggerContext ctx, Player player, java.util.function.Consumer<Player> action) {
        if (player == null) {
            return;
        }
        Runnable run = () -> {
            Player current = Bukkit.getPlayer(ctx.playerId());
            if (current != null) {
                action.accept(current);
            }
        };
        if ("player_death".equals(ctx.eventKey()) && player.isDead()) {
            pendingRespawn.put(ctx.playerId(), run);
            return;
        }
        long delayTicks = switch (ctx.eventKey()) {
            case "player_death" -> 20;   // one breath after respawn
            case "found_diamonds" -> 15; // the deliberate beat
            default -> 1;
        };
        sync(run, delayTicks);
    }

    /** DeathListener calls this on PlayerRespawnEvent. */
    public void flushPendingAfterRespawn(UUID playerId) {
        Runnable pending = pendingRespawn.remove(playerId);
        if (pending != null) {
            sync(pending, 20);
        }
    }

    public void dropPending(UUID playerId) {
        pendingRespawn.remove(playerId);
        recentQuips.remove(playerId);
    }

    // ------------------------------------------------------------ levity

    /** AI quip if allowed and it survives LevityGuard; else curated pool. */
    private String chooseQuip(TriggerContext ctx, Interpretation interp) {
        if (config.levityAi && LevityGuard.valid(interp.quip())) {
            return interp.quip().trim();
        }
        Set<String> recent = recentQuips.computeIfAbsent(ctx.playerId(), k -> new LinkedHashSet<>());
        HumorPool.Quip quip = humor.pick(ctx.cause(), recent, random);
        if (quip == null) {
            return null;
        }
        recent.add(quip.id());
        return quip.text();
    }

    // ------------------------------------------------------------ helpers

    private List<Player> recipients(TriggerContext ctx) {
        List<Player> out = new ArrayList<>();
        if ("thunderstorm".equals(ctx.eventKey()) && ctx.worldKey() != null) {
            var world = Bukkit.getWorld(ctx.worldKey());
            if (world != null) {
                world.getPlayers().forEach(out::add);
            }
        } else if ("fellowship".equals(ctx.eventKey()) && ctx.pairKey() != null) {
            for (String id : ctx.pairKey().split("\\|")) {
                Player p = Bukkit.getPlayer(UUID.fromString(id));
                if (p != null) {
                    out.add(p);
                }
            }
        }
        if (out.isEmpty()) {
            Player anchor = Bukkit.getPlayer(ctx.playerId());
            if (anchor != null) {
                out.add(anchor);
            }
        }
        return out;
    }

    private void markSeen(UUID playerId, ShownMoment moment) {
        verseMemory.markSeen(playerId, moment.passage().ref(), moment.at());
        StoryMemory story = stories.apply(playerId);
        if (story != null) {
            story.addSeenRef(moment.passage().ref());
            story.countTheme(moment.interp().emphasis());
        }
    }

    private String frameFor(String eventKey) {
        FallbackPool.EventDefault defaults = pool.defaultsFor(eventKey);
        return defaults == null ? "" : defaults.frame();
    }

    private String tierOf(String eventKey) {
        return switch (eventKey) {
            case "eating_bread", "sleep" -> "minor";
            case "player_death", "found_diamonds", "first_join" -> "major";
            default -> "standard";
        };
    }

    public ShownMoment lastShown(UUID playerId) {
        return lastShown.get(playerId);
    }

    public Presenter presenter() {
        return presenter;
    }

    private void sync(Runnable run, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, run, delayTicks);
    }
}
