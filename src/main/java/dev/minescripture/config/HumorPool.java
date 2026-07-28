package dev.minescripture.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minescripture.util.JsonUtil;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Curated, 100% human-written levity pool (humor.json). Served only on
 * tone-light moments; never formatted like Scripture.
 */
public final class HumorPool {

    public record Quip(String id, Set<String> causes, String text) {
    }

    private final List<Quip> quips;

    private HumorPool(List<Quip> quips) {
        this.quips = Collections.unmodifiableList(quips);
    }

    public static HumorPool load(Reader reader) {
        JsonObject root = JsonUtil.parseObject(reader);
        List<Quip> out = new ArrayList<>();
        for (JsonElement el : JsonUtil.array(root, "quips")) {
            JsonObject q = el.getAsJsonObject();
            out.add(new Quip(
                    JsonUtil.str(q, "id", "quip-" + out.size()),
                    JsonUtil.strSet(q, "causes"),
                    q.get("text").getAsString()
            ));
        }
        return new HumorPool(out);
    }

    public List<Quip> all() {
        return quips;
    }

    /**
     * Prefer a cause-matched quip not recently used; then any not recently used;
     * then any cause-matched; then anything.
     */
    public Quip pick(String cause, Set<String> recentIds, Random random) {
        Quip best = pickFrom(q -> matches(q, cause) && !recentIds.contains(q.id()), random);
        if (best == null) {
            best = pickFrom(q -> !recentIds.contains(q.id()), random);
        }
        if (best == null) {
            best = pickFrom(q -> matches(q, cause), random);
        }
        if (best == null && !quips.isEmpty()) {
            best = quips.get(random.nextInt(quips.size()));
        }
        return best;
    }

    private boolean matches(Quip q, String cause) {
        return cause != null && q.causes().contains(cause);
    }

    private Quip pickFrom(java.util.function.Predicate<Quip> filter, Random random) {
        List<Quip> pool = quips.stream().filter(filter).toList();
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }
}
