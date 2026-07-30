package dev.minescripture.select;

import com.google.gson.JsonObject;
import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.trigger.StoryMemory;
import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import dev.minescripture.util.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Gloo understands the moment: interprets the session story and recommends
 * Scripture references. It NEVER writes text shown to players — the refs it
 * returns are normalized, validated and fetched verbatim from YouVersion
 * downstream. The optional quip (tone: light only) must pass LevityGuard
 * before anyone sees it.
 */
public final class MomentInterpreter implements TriggerService.InterpretationSource {

    static final Set<String> TONES = Set.of("solemn", "warm", "awe", "calm", "encouraging", "light");

    static final String SYSTEM_PROMPT = """
            You interpret meaningful moments in a player's game session story and recommend Scripture.
            You never write Scripture text shown to players — you only point to it by reference.

            OUTPUT FORMAT — strictly valid raw JSON matching this schema. No markdown fences,
            no conversational filler, nothing before or after the JSON object:
            {"resonance": "<one or two words for what this moment is about>",
             "emotional_arc": "<short snake_case arc like setback_to_hope>",
             "emphasis": "<the single emotional nuance the Scripture should carry, e.g. comfort, perseverance, gratitude>",
             "tone": "<see TONE VALUES — exactly one of the six allowed strings>",
             "recommended_refs": ["2 to 4 references, ranked best first"],
             "quip": "<OPTIONAL, ONLY when tone is light — see QUIP RULES>",
             "reasoning": "<one short sentence, for server logs only>"}

            RECOMMENDED_REFS RULES:
            - Each reference MUST use uppercase USFM book codes with period separators:
              BOOK.CHAPTER.VERSE or BOOK.CHAPTER.START-END (e.g. "LAM.3.22-23", "PSA.23.1-3", "JHN.3.16").
            - USFM codes: GEN, EXO, PSA, PRO, ECC, SNG, ISA, LAM, MAT, MRK, LUK, JHN, ROM, 1CO, 2CO, PHP, 1JN, REV, ...
            - PREFER A SINGLE VERSE. The player has a few seconds to read this in the
              middle of playing, so brevity is part of whether it lands at all.
              Use two verses only when the second is genuinely needed to finish the
              thought. Never more than two — longer ranges are rejected outright.
            - Where two passages fit equally well, choose the shorter one. Scripture
              has no shortage of brief, potent verses.
            - Output ONLY reference codes in recommended_refs — never Bible text.
            - All of Scripture is in range — prefer fitting-but-fresh passages over famous cliches,
              and avoid anything in seen_refs.

            TONE VALUES — output exactly one of these lowercase strings, nothing else:
            "solemn", "warm", "awe", "calm", "encouraging", "light".
            Any other value is invalid and will be discarded.

            QUIP RULES (only when tone is "light"):
            - It must be BIBLE-FLAVOURED. Lean on a biblical person, place or story —
              Jonah, Balaam's donkey, Jericho, the Red Sea, Daniel's lions, Noah's flood —
              or on the cadence of a proverb. A purely Minecraft joke with no scriptural
              texture is not what this is for.
            - Set that against what just happened to the player. The humour lives in the
              gap between the two: "Even Balaam's donkey would have seen that cactus."
            - Max 120 characters, plain text only.
            - No quotation marks. No BOOK titles (Genesis, John, Psalms) and no digits, so
              it can never read as a citation — but biblical NAMES are exactly what you want.
            - Dry and observational. No cheesy puns, no exclamation marks.
            - Gentle and kind — a smile at the mishap, never mockery of the player, and
              never mockery of Scripture.

            Tone judgment matters: a comedic mishap (cactus hug, falling into your own farm pit) is "light";
            genuine loss, first-time wonder, danger survived, and fellowship are never "light".
            """;

    private final GlooClient gloo;
    private final MineScriptureConfig config;
    private final Consumer<String> log;

    public MomentInterpreter(GlooClient gloo, MineScriptureConfig config, Consumer<String> log) {
        this.gloo = gloo;
        this.config = config;
        this.log = log;
    }

    @Override
    public CompletableFuture<Interpretation> interpret(TriggerContext ctx, StoryMemory story) {
        if (!config.hasGloo()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Gloo credentials not configured"));
        }
        return gloo.complete(SYSTEM_PROMPT, buildUserMessage(ctx, story))
                .thenApply(content -> {
                    Interpretation interp = parse(content);
                    if (interp == null) {
                        throw new IllegalStateException("Unparseable interpretation");
                    }
                    if (interp.reasoning() != null && !interp.reasoning().isBlank()) {
                        log.accept("[" + ctx.eventKey() + "] Gloo reasoning: " + interp.reasoning());
                    }
                    return interp;
                });
    }

    /** Anonymous gameplay context only: event + cause + story window/aggregates. */
    String buildUserMessage(TriggerContext ctx, StoryMemory story) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("event", ctx.eventKey());
        if (ctx.cause() != null && !ctx.cause().isBlank()) {
            msg.put("cause", ctx.cause());
        }
        msg.put("session_story", story.toContext(System.currentTimeMillis(), 8));
        return JsonUtil.GSON.toJson(msg);
    }

    /**
     * Models invent tones. A live call returned "supportive_resilience", which a
     * strict enum check silently flattened to "solemn" — and because Levity needs
     * exactly "light", that made comedic moments unreachable. Rather than discard
     * the judgment, map it onto the nearest tone we actually present. Same rule as
     * everywhere else here: the AI is trusted for judgment, not for format.
     */
    static String normalizeTone(String raw) {
        if (raw == null) {
            return "solemn";
        }
        String t = raw.toLowerCase(Locale.ROOT);
        if (TONES.contains(t)) {
            return t;
        }
        if (containsAny(t, "light", "humor", "humour", "funny", "comic", "playful", "silly", "amus", "wry")) {
            return "light";
        }
        if (containsAny(t, "awe", "wonder", "majest", "reveren", "glor", "sublime")) {
            return "awe";
        }
        if (containsAny(t, "calm", "peace", "quiet", "still", "seren", "rest", "gentle")) {
            return "calm";
        }
        if (containsAny(t, "encourag", "resilien", "hope", "strength", "persever", "support", "determin", "triumph")) {
            return "encouraging";
        }
        if (containsAny(t, "warm", "tender", "affection", "comfort", "grateful", "gratitude", "joy")) {
            return "warm";
        }
        return "solemn";
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** Lenient parse + shape validation. Null on anything unusable. */
    static Interpretation parse(String content) {
        JsonObject o = JsonUtil.firstJsonObject(content);
        if (o == null) {
            return null;
        }
        List<String> refs = new ArrayList<>(JsonUtil.strList(o, "recommended_refs"));
        if (refs.isEmpty()) {
            return null;
        }
        if (refs.size() > 4) {
            refs = refs.subList(0, 4);
        }
        String tone = normalizeTone(JsonUtil.str(o, "tone", "solemn"));
        String quip = JsonUtil.str(o, "quip", null);
        if (!"light".equals(tone)) {
            quip = null; // quips exist only on light moments, by contract
        }
        return new Interpretation(
                JsonUtil.str(o, "resonance", "presence"),
                JsonUtil.str(o, "emotional_arc", "moment"),
                JsonUtil.str(o, "emphasis", "hope"),
                tone,
                List.copyOf(refs),
                quip,
                JsonUtil.str(o, "reasoning", null));
    }
}
