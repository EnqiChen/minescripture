package dev.minescripture.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minescripture.select.Interpretation;
import dev.minescripture.util.JsonUtil;

import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads fallback.json: the ~40-ref curated pool whose {themes, tone} metadata
 * doubles as the CandidateScorer's semantic-fit table, plus per-event defaults
 * (refs + human-written frame + default interpretation) for cold start and the
 * never-fail chain. Verse TEXT is never stored here — always YouVersion.
 */
public final class FallbackPool {

    public record VerseMeta(String ref, String display, Set<String> events, Set<String> themes, String tone) {
    }

    /**
     * @param framesByPhase optional time-of-day variants of {@code frame}, keyed
     *                      dawn/day/dusk/night. A frame must never claim a time
     *                      of day that isn't true when the player reads it.
     */
    public record EventDefault(List<String> refs, String frame, Map<String, String> framesByPhase,
                               Interpretation interpretation) {

        /** The variant for this phase of the day, or the neutral frame. */
        public String frameFor(String phase) {
            return framesByPhase.getOrDefault(phase, frame);
        }
    }

    /**
     * Underground there is no sky to describe, so the time of day is not a fact
     * the player can check. Dying in a deep cave at dawn and being told "you
     * awaken at dawn" is true and still absurd.
     */
    public static String phaseOf(long worldTime, boolean canSeeSky) {
        return canSeeSky ? phaseOf(worldTime) : "underground";
    }

    /** Minecraft's 24000-tick day, in the words a frame would use. */
    public static String phaseOf(long worldTime) {
        long t = ((worldTime % 24_000L) + 24_000L) % 24_000L;
        if (t >= 23_000L || t < 2_000L) {
            return "dawn";
        }
        if (t < 11_500L) {
            return "day";
        }
        if (t < 13_500L) {
            return "dusk";
        }
        return "night";
    }

    private final Map<String, VerseMeta> byRef;
    private final Map<String, EventDefault> eventDefaults;

    private FallbackPool(Map<String, VerseMeta> byRef, Map<String, EventDefault> eventDefaults) {
        this.byRef = Collections.unmodifiableMap(byRef);
        this.eventDefaults = Collections.unmodifiableMap(eventDefaults);
    }

    public static FallbackPool load(Reader reader) {
        JsonObject root = JsonUtil.parseObject(reader);
        Map<String, VerseMeta> byRef = new LinkedHashMap<>();
        for (JsonElement el : JsonUtil.array(root, "verses")) {
            JsonObject v = el.getAsJsonObject();
            String ref = v.get("ref").getAsString();
            byRef.put(ref, new VerseMeta(
                    ref,
                    JsonUtil.str(v, "display", ref),
                    JsonUtil.strSet(v, "events"),
                    JsonUtil.strSet(v, "themes"),
                    JsonUtil.str(v, "tone", "solemn")
            ));
        }
        Map<String, EventDefault> defaults = new LinkedHashMap<>();
        JsonObject defs = root.getAsJsonObject("event_defaults");
        if (defs != null) {
            for (String event : defs.keySet()) {
                JsonObject d = defs.getAsJsonObject(event);
                JsonObject interp = d.getAsJsonObject("interpretation");
                List<String> refs = JsonUtil.strList(d, "refs");
                Map<String, String> frames = new LinkedHashMap<>();
                JsonObject framesJson = d.getAsJsonObject("frames");
                if (framesJson != null) {
                    for (String phase : framesJson.keySet()) {
                        frames.put(phase, framesJson.get(phase).getAsString());
                    }
                }
                defaults.put(event, new EventDefault(
                        List.copyOf(refs),
                        JsonUtil.str(d, "frame", ""),
                        Map.copyOf(frames),
                        new Interpretation(
                                JsonUtil.str(interp, "resonance", "presence"),
                                JsonUtil.str(interp, "emotional_arc", "moment"),
                                JsonUtil.str(interp, "emphasis", "hope"),
                                JsonUtil.str(interp, "tone", "solemn"),
                                List.copyOf(refs),
                                null,
                                "curated default"
                        )
                ));
            }
        }
        return new FallbackPool(byRef, defaults);
    }

    public VerseMeta metadata(String ref) {
        return byRef.get(ref);
    }

    public Map<String, VerseMeta> verses() {
        return byRef;
    }

    public EventDefault defaultsFor(String eventKey) {
        return eventDefaults.get(eventKey);
    }

    public Map<String, EventDefault> allDefaults() {
        return eventDefaults;
    }

    /** Default interpretation for an event — the cold-start / Gloo-failure path. */
    public Interpretation defaultInterpretation(String eventKey) {
        EventDefault d = eventDefaults.get(eventKey);
        return d == null ? null : d.interpretation();
    }

    /** First default ref for the event not yet seen this session; else the first default. */
    public String pickFallbackRef(String eventKey, Set<String> seenRefs) {
        EventDefault d = eventDefaults.get(eventKey);
        if (d == null || d.refs().isEmpty()) {
            return null;
        }
        for (String ref : d.refs()) {
            if (!seenRefs.contains(ref)) {
                return ref;
            }
        }
        return d.refs().get(0);
    }

    /** Also used by the JSON asset self-tests. */
    static JsonArray rawVerses(JsonObject root) {
        return JsonUtil.array(root, "verses");
    }
}
