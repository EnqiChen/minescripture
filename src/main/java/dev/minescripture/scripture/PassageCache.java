package dev.minescripture.scripture;

import com.google.gson.reflect.TypeToken;
import dev.minescripture.util.JsonUtil;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Memory + disk passage cache: repeat moments are network-free, and the disk
 * layer is the last link of the never-fail chain (a prefetched fallback pool
 * survives a total outage). Keys include the bible id so translations never
 * cross-contaminate.
 */
public final class PassageCache {

    private static final Type MAP_TYPE = new TypeToken<Map<String, Passage>>() {
    }.getType();

    private final Path file;
    private final Map<String, Passage> memory = new ConcurrentHashMap<>();

    public PassageCache(Path dataDir) {
        this.file = dataDir.resolve("passages.json");
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Passage> loaded = JsonUtil.GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                memory.putAll(loaded);
            }
        } catch (IOException | RuntimeException e) {
            // Corrupt cache: rebuild over time rather than fail.
            memory.clear();
        }
    }

    public Optional<Passage> get(int bibleId, String ref) {
        return Optional.ofNullable(memory.get(key(bibleId, ref)));
    }

    public void put(int bibleId, Passage passage) {
        memory.put(key(bibleId, passage.ref()), passage);
        persist();
    }

    /** Cache hit is instant; a miss runs the loader and caches its result. */
    public CompletableFuture<Passage> getOrFetch(int bibleId, String ref, Supplier<CompletableFuture<Passage>> loader) {
        Passage hit = memory.get(key(bibleId, ref));
        if (hit != null) {
            return CompletableFuture.completedFuture(hit);
        }
        return loader.get().thenApply(passage -> {
            put(bibleId, passage);
            return passage;
        });
    }

    public int size() {
        return memory.size();
    }

    private synchronized void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                JsonUtil.GSON.toJson(memory, MAP_TYPE, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Disk trouble must never break a Scripture moment; memory still serves.
        }
    }

    private static String key(int bibleId, String ref) {
        return bibleId + ":" + ref;
    }
}
