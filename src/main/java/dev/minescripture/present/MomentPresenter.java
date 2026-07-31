package dev.minescripture.present;

import dev.minescripture.config.EventSpec;
import dev.minescripture.config.EventSpecs;
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
import dev.minescripture.trigger.PlayerStateManager;
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
import java.util.concurrent.TimeUnit;
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

    /** Where a quip actually came from — not what the config hoped for. */
    public enum QuipSource { GLOO, CURATED }

    public record ShownMoment(TriggerContext ctx, Interpretation interp, TriggerService.Origin origin,
                              Passage passage, String quip, QuipSource quipSource, String frame, long at) {
        public boolean isLevity() {
            return quip != null;
        }
    }

    private record ChosenQuip(String text, QuipSource source) {
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
    private final PlayerStateManager playerState;
    private final EventSpecs specs;
    private final Random random = new Random();

    private Function<UUID, StoryMemory> stories = id -> null; // bound after TriggerService exists

    private final Map<UUID, Runnable> pendingRespawn = new ConcurrentHashMap<>();
    private final Map<UUID, ShownMoment> lastShown = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> recentQuips = new ConcurrentHashMap<>();
    /** When each player last had something put in front of them, by wall clock. */
    private final Map<UUID, Long> lastShownAt = new ConcurrentHashMap<>();
    /** Last wording used per player+event, so a recurring moment varies its greeting. */
    private final Map<String, String> lastWording = new ConcurrentHashMap<>();

    /**
     * Two verses inside this window read as a malfunction whatever the gate
     * thought. The gate paces SUBMITS, but a moment can spend nine seconds being
     * interpreted, so submits that were properly spaced can still land together.
     * This is the last line, measured in screen time rather than intent.
     */
    private static final long SCREEN_GUARD_MS = 5_000L;

    public MomentPresenter(Plugin plugin, MineScriptureConfig config, FallbackPool pool, HumorPool humor,
                           TriggerPolicy policy, RefValidator validator, SessionVerseMemory verseMemory,
                           PassageCache passageCache, ScriptureClient scripture, Presenter presenter,
                           SessionJournal journal, PlayerStateManager playerState, EventSpecs specs) {
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
        this.playerState = playerState;
        this.specs = specs;
    }

    /** Called once by the plugin after TriggerService is constructed. */
    public void bindStories(Function<UUID, StoryMemory> stories) {
        this.stories = stories;
    }

    // ------------------------------------------------------------------ sink

    @Override
    public void present(TriggerContext ctx, Interpretation interp, TriggerService.Origin origin, int deathDepth) {
        Player player = Bukkit.getPlayer(ctx.playerId());
        if (player == null) {
            return;
        }
        // Levity branch: Gloo judged this moment comedic. Its tone judgement is
        // necessary but not sufficient — a live call once called a first diamond
        // find "lighthearted", and a discovery is never a joke however the model
        // reports it. Only mishap-shaped events are eligible at all.
        if (interp.isLight() && config.levity && levityEligible(ctx.eventKey())
                && (ctx.isDemo() || policy.levityAllowed(ctx.playerId(), ctx.at()))) {
            ChosenQuip chosen = chooseQuip(ctx, interp);
            if (chosen != null && !tooSoonFor(ctx.playerId(), ctx.eventKey())) {
                policy.markLevity(ctx.playerId(), ctx.at());
                lastShownAt.put(ctx.playerId(), System.currentTimeMillis());
                log.info("[" + ctx.eventKey() + "] shown to [" + player.getName() + "]: levity ["
                        + chosen.source() + "]");
                ShownMoment moment = new ShownMoment(ctx, interp, origin, null,
                        chosen.text(), chosen.source(), null, ctx.at());
                lastShown.put(ctx.playerId(), moment);
                deliverTimed(ctx, player, player.isDead(), p -> presenter.showLevity(p, chosen.text()));
                return;
            }
        }
        // Whether the player is still on the death screen has to be decided HERE,
        // on the main thread, while it is still true. Resolving a verse takes
        // seconds, by which time they may well have respawned already.
        boolean deadAtMoment = player.isDead();
        // A near-death moment takes seconds to resolve, and the player may be dead
        // by the time it arrives. Remember where the story was so we can tell.
        StoryMemory story = stories.apply(ctx.playerId());
        int deathsAtSubmit = story == null ? 0 : story.deaths();

        selectVerse(ctx, interp)
                // One deadline over the whole tail, not just the Gloo leg: ref
                // validation and passage fetches are each their own round trip.
                .orTimeout(config.timeoutSuddenMs, TimeUnit.MILLISECONDS)
                .whenComplete((passage, err) -> onMain(() -> {
                    if (err != null) {
                        log.warning("[" + ctx.eventKey() + "] presentation failed: " + err);
                        return;
                    }
                    if (passage == null || passage.isEmpty()) {
                        log.warning("[" + ctx.eventKey() + "] no passage available (no key/cache?) — moment skipped");
                        return;
                    }
                    deliverVerse(ctx, interp, origin, passage.get(), deathDepth, deadAtMoment, deathsAtSubmit);
                }));
    }

    /**
     * Everything downstream of an HTTP call completes on a ForkJoinPool worker,
     * and almost the whole Bukkit API is main-thread-only. Hop back at the top of
     * the continuation so no Bukkit call below here is ever made off-thread.
     */
    private void onMain(Runnable run) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            run.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, run);
        }
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

    /**
     * The frame is resolved here rather than earlier: on the main thread, at the
     * instant the player actually reads it. A death verse held back until respawn
     * must describe the world they wake into, not the one they died in.
     */
    private void deliverVerse(TriggerContext ctx, Interpretation interp, TriggerService.Origin origin,
                              Passage passage, int deathDepth, boolean deadAtMoment, int deathsAtSubmit) {
        deliverTimed(ctx, Bukkit.getPlayer(ctx.playerId()), deadAtMoment, anchor -> {
            FallbackPool.Variant wording = wordingFor(ctx);
            String frame = frameFor(ctx.eventKey(), anchor.getWorld().getTime(),
                    anchor.getLocation().getBlock().getLightFromSky() > 0, wording);
            ShownMoment moment = new ShownMoment(ctx, interp, origin, passage, null, null, frame, ctx.at());
            boolean isDeath = "player_death".equals(ctx.eventKey());
            // A death supersedes anything still in flight for the same incident:
            // "you survived" delivered over a corpse is worse than saying nothing.
            StoryMemory now = stories.apply(ctx.playerId());
            if (!isDeath && now != null && now.deaths() > deathsAtSubmit) {
                log.info("[" + ctx.eventKey() + "] dropped — the player died while it was"
                        + " being written; the death has its own moment");
                return;
            }
            List<Player> to = new ArrayList<>(recipients(ctx));
            if (!isDeath) {
                to.removeIf(p -> tooSoonFor(p.getUniqueId(), ctx.eventKey()));
            }
            if (to.isEmpty()) {
                return;
            }
            long at = System.currentTimeMillis();
            to.forEach(p -> lastShownAt.put(p.getUniqueId(), at));
            log.info("[" + ctx.eventKey() + "] shown to "
                    + to.stream().map(Player::getName).toList() + ": "
                    + passage.display() + " [" + origin + "]");
            for (Player recipient : to) {
                show(recipient, ctx, moment, deathDepth);
                markSeen(recipient.getUniqueId(), moment);
            }
            lastShown.put(ctx.playerId(), moment);
        });
    }

    private void show(Player player, TriggerContext ctx, ShownMoment moment, int deathDepth) {
        switch (tierOf(ctx.eventKey(), deathDepth)) {
            case MINOR -> presenter.showMinor(player, moment.passage());
            case MAJOR -> presenter.showMajor(player, titleFor(ctx), moment.frame(), moment.passage());
            default -> presenter.showStandard(player, moment.frame(), moment.passage());
        }
        journal.add(player.getUniqueId(), ctx.eventKey(), moment.passage());
    }

    // ------------------------------------------------------------ timing

    /**
     * Timing to the moment: a dead player's verse waits for the respawn screen to
     * clear; diamonds get a deliberate one-beat delay that reads as reverence,
     * not lag; everything else shows now. Callers are already on the main thread,
     * so the liveness checks here are reliable.
     */
    private void deliverTimed(TriggerContext ctx, Player player, boolean deadAtMoment,
                              java.util.function.Consumer<Player> action) {
        if (player == null) {
            log.warning("[" + ctx.eventKey() + "] player left before the verse resolved — moment dropped");
            return;
        }
        Runnable run = () -> {
            Player current = Bukkit.getPlayer(ctx.playerId());
            if (current != null) {
                action.accept(current);
            }
        };
        // Resolving a verse takes seconds, so a player who died may already be
        // back on their feet. Hold only if they are STILL on the death screen.
        if ("player_death".equals(ctx.eventKey()) && (deadAtMoment || player.isDead())) {
            if (player.isDead()) {
                pendingRespawn.put(ctx.playerId(), run);
                return;
            }
            sync(run, 20); // respawned while we were thinking — one breath, then speak
            return;
        }
        long delayTicks = switch (ctx.eventKey()) {
            case "found_diamonds" -> 15; // the deliberate beat
            // Sleeping is the one moment a player has nothing to do and the screen
            // has gone quiet. Let the fade settle, then put it in front of them.
            case "sleep" -> 25;
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

    /**
     * True if this player has just been shown something. Announces itself loudly:
     * a near-miss here is a bug in the gate that nothing else would surface.
     */
    private boolean tooSoonFor(UUID playerId, String eventKey) {
        Long previous = lastShownAt.get(playerId);
        if (previous == null || System.currentTimeMillis() - previous >= SCREEN_GUARD_MS) {
            return false;
        }
        log.warning("[" + eventKey + "] DOUBLE PREVENTED — that player was shown something "
                + (System.currentTimeMillis() - previous) + "ms ago. The gate let two moments"
                + " through for what is probably one incident.");
        return true;
    }

    private boolean levityEligible(String eventKey) {
        EventSpec spec = specs.get(eventKey);
        return spec != null && spec.levityEligible();
    }

    /**
     * Gloo's own quip is the primary path — writing the joke is the point of
     * asking a model to judge the moment. The curated pool is the safety net for
     * when the quip is missing or fails the guard, and which one was used is
     * recorded rather than inferred: /msc explain must not claim the AI wrote
     * something a human did.
     */
    private ChosenQuip chooseQuip(TriggerContext ctx, Interpretation interp) {
        if (config.levityAi) {
            if (LevityGuard.valid(interp.quip())) {
                String text = interp.quip().trim();
                log.info("[" + ctx.eventKey() + "] levity from Gloo: " + text);
                return new ChosenQuip(text, QuipSource.GLOO);
            }
            if (interp.quip() != null) {
                log.info("[" + ctx.eventKey() + "] Gloo's quip was rejected by LevityGuard, "
                        + "using the curated pool: " + interp.quip());
            }
        }
        Set<String> recent = recentQuips.computeIfAbsent(ctx.playerId(), k -> new LinkedHashSet<>());
        HumorPool.Quip quip = humor.pick(ctx.cause(), recent, random);
        if (quip == null) {
            return null;
        }
        recent.add(quip.id());
        log.info("[" + ctx.eventKey() + "] levity from the curated pool: " + quip.id());
        return new ChosenQuip(quip.text(), QuipSource.CURATED);
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
        // World and pair moments are gated on one anchor player, so every other
        // recipient reaches here ungated. Mute is a promise to the individual —
        // honour it per person, not per broadcast.
        out.removeIf(p -> playerState.isMuted(p.getUniqueId()));
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

    /** A few words for the big on-screen line; the sentence stays in chat. */
    private String titleFor(TriggerContext ctx) {
        FallbackPool.EventDefault defaults = pool.defaultsFor(ctx.eventKey());
        if (defaults == null) {
            return "";
        }
        String remembered = lastWording.get(ctx.playerId() + ":" + ctx.eventKey());
        return remembered != null ? remembered : defaults.titleOrShortFrame();
    }

    /** Chosen once per moment and remembered, so title and frame agree. */
    private FallbackPool.Variant wordingFor(TriggerContext ctx) {
        FallbackPool.EventDefault defaults = pool.defaultsFor(ctx.eventKey());
        if (defaults == null) {
            return new FallbackPool.Variant("", "");
        }
        String key = ctx.playerId() + ":" + ctx.eventKey();
        FallbackPool.Variant chosen = defaults.variant(random, lastWording.get(key));
        lastWording.put(key, chosen.title());
        return chosen;
    }

    /**
     * Time-of-day wording still wins where it exists — saying "at dawn" when it is
     * not dawn is worse than saying the same thing twice.
     */
    private String frameFor(String eventKey, long worldTime, boolean canSeeSky,
                            FallbackPool.Variant wording) {
        FallbackPool.EventDefault defaults = pool.defaultsFor(eventKey);
        if (defaults == null) {
            return "";
        }
        String phase = FallbackPool.phaseOf(worldTime, canSeeSky);
        String byPhase = defaults.framesByPhase().get(phase);
        return byPhase != null ? byPhase : wording.frame();
    }

    /**
     * Repeated deaths in one bad run keep their verse but lose the ceremony:
     * the title is for the death that mattered, not the fourth in a row.
     */
    /**
     * Read from events.json rather than hardcoded here, so presentation can be
     * tuned by editing data. The one exception is earned at runtime: repeated
     * deaths in one bad run keep their verse but lose the ceremony.
     */
    private EventSpec.Tier tierOf(String eventKey, int deathDepth) {
        if ("player_death".equals(eventKey) && deathDepth > 0) {
            return EventSpec.Tier.STANDARD;
        }
        EventSpec spec = specs.get(eventKey);
        return spec == null ? EventSpec.Tier.STANDARD : spec.tier();
    }

    public ShownMoment lastShown(UUID playerId) {
        return lastShown.get(playerId);
    }

    public Presenter presenter() {
        return presenter;
    }

    private void sync(Runnable run, long delayTicks) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, run, delayTicks);
        }
    }
}
