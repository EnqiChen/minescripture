package dev.minescripture.select;

import dev.minescripture.config.FallbackPool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Java's existence gate: normalize → check against YouVersion (200 accept,
 * 404 reject → next candidate). The checker is injected so this stays pure;
 * ScriptureClient supplies the real one (and validation warms the passage
 * cache as a side effect). Refs already validated — or in the curated pool —
 * skip the network entirely. On a network error (empty Optional) the ref is
 * skipped: the never-fail chain below (FallbackPool → disk cache) covers it.
 */
public final class RefValidator {

    /** 200 → true, 404 → false, network trouble → empty. */
    public interface ExistenceChecker extends Function<String, CompletableFuture<Optional<Boolean>>> {
    }

    private final Set<String> validated = ConcurrentHashMap.newKeySet();
    private final Set<String> knownBad = ConcurrentHashMap.newKeySet();
    private final ExistenceChecker checker;

    public RefValidator(FallbackPool pool, ExistenceChecker checker) {
        this.checker = checker;
        validated.addAll(pool.verses().keySet()); // curated refs are pre-trusted
    }

    /**
     * Normalizes raw model refs (dropping garbage), then resolves existence in
     * order, sequentially — no API hammering. Result preserves Gloo's ranking.
     */
    public CompletableFuture<List<String>> filterValid(List<String> rawRefs) {
        List<String> canonical = new ArrayList<>(new LinkedHashSet<>(rawRefs.stream()
                .map(RefNormalizer::normalize)
                .flatMap(Optional::stream)
                .toList()));
        return resolveNext(canonical, 0, new ArrayList<>());
    }

    private CompletableFuture<List<String>> resolveNext(List<String> refs, int index, List<String> accepted) {
        if (index >= refs.size()) {
            return CompletableFuture.completedFuture(accepted);
        }
        String ref = refs.get(index);
        if (validated.contains(ref)) {
            accepted.add(ref);
            return resolveNext(refs, index + 1, accepted);
        }
        if (knownBad.contains(ref)) {
            return resolveNext(refs, index + 1, accepted);
        }
        return checker.apply(ref)
                .exceptionally(err -> Optional.empty())
                .thenCompose(exists -> {
                    if (exists.isPresent()) {
                        if (exists.get()) {
                            validated.add(ref);
                            accepted.add(ref);
                        } else {
                            knownBad.add(ref);
                        }
                    }
                    // empty = network trouble: skip, never block the moment
                    return resolveNext(refs, index + 1, accepted);
                });
    }

    /** Previously validated refs — the scorer's reliability signal. */
    public Set<String> validatedRefs() {
        return Set.copyOf(validated);
    }
}
