package dev.minescripture.config;

import com.google.gson.JsonObject;
import dev.minescripture.util.JsonUtil;

import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Loads and serves events.json. */
public final class EventSpecs {

    private final Map<String, EventSpec> specs;

    private EventSpecs(Map<String, EventSpec> specs) {
        this.specs = Collections.unmodifiableMap(specs);
    }

    public static EventSpecs load(Reader reader) {
        JsonObject root = JsonUtil.parseObject(reader);
        JsonObject events = root.getAsJsonObject("events");
        Map<String, EventSpec> out = new LinkedHashMap<>();
        for (String key : events.keySet()) {
            JsonObject e = events.getAsJsonObject(key);
            out.put(key, new EventSpec(
                    key,
                    EventSpec.Path.valueOf(JsonUtil.str(e, "path", "predictable").toUpperCase(Locale.ROOT)),
                    EventSpec.Tier.valueOf(JsonUtil.str(e, "tier", "standard").toUpperCase(Locale.ROOT)),
                    JsonUtil.bool(e, "priority", false),
                    JsonUtil.integer(e, "cooldown_s", 0),
                    JsonUtil.integer(e, "debounce_s", 0),
                    parseOnce(JsonUtil.str(e, "once", "none")),
                    "world".equalsIgnoreCase(JsonUtil.str(e, "scope", "player"))
                            ? EventSpec.Scope.WORLD : EventSpec.Scope.PLAYER
            ));
        }
        return new EventSpecs(out);
    }

    private static EventSpec.Once parseOnce(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "persistent" -> EventSpec.Once.PERSISTENT;
            case "session_pair" -> EventSpec.Once.SESSION_PAIR;
            default -> EventSpec.Once.NONE;
        };
    }

    public EventSpec get(String key) {
        return specs.get(key);
    }

    public Map<String, EventSpec> all() {
        return specs;
    }
}
