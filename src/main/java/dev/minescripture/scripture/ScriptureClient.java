package dev.minescripture.scripture;

import com.google.gson.JsonObject;
import dev.minescripture.select.RefValidator;
import dev.minescripture.util.Http;
import dev.minescripture.util.JsonUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * YouVersion Platform passages client — the single source of every word of
 * Scripture a player reads, and (via 200/404) the source-of-truth existence
 * validator. Internal refs like LAM.3.22-23 are tried as-is first; on 404 the
 * expanded range syntax LAM.3.22-LAM.3.23 is retried automatically.
 */
public final class ScriptureClient {

    public static final String DEFAULT_BASE = "https://api.youversion.com";

    /** 404 from YouVersion: the reference does not exist in this Bible. */
    public static final class PassageNotFound extends RuntimeException {
        public PassageNotFound(String ref) {
            super("Passage not found: " + ref);
        }
    }

    private static final Map<Integer, String> TRANSLATIONS = Map.of(
            3034, "BSB",
            111, "NIV",
            1, "KJV");

    private final Http http;
    private final String baseUrl;
    private final String appKey;
    private final int bibleId;
    private final long timeoutMs;

    public ScriptureClient(Http http, String baseUrl, String appKey, int bibleId, long timeoutMs) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.appKey = appKey;
        this.bibleId = bibleId;
        this.timeoutMs = timeoutMs;
    }

    public CompletableFuture<Passage> fetch(String canonicalRef) {
        return get(canonicalRef).thenCompose(resp -> {
            if (resp.ok()) {
                return CompletableFuture.completedFuture(parse(canonicalRef, resp.body()));
            }
            String expanded = expandedRange(canonicalRef);
            if (resp.status() == 404 && expanded != null) {
                return get(expanded).thenApply(retry -> {
                    if (retry.ok()) {
                        return parse(canonicalRef, retry.body());
                    }
                    throw notFoundOrError(canonicalRef, retry.status());
                });
            }
            throw notFoundOrError(canonicalRef, resp.status());
        });
    }

    /**
     * Existence gate for RefValidator: 200 → true, 404 → false, anything else
     * (auth trouble, outage) → empty so the never-fail chain takes over.
     */
    public RefValidator.ExistenceChecker existenceChecker(PassageCache cache) {
        return ref -> cache.getOrFetch(bibleId, ref, () -> fetch(ref))
                .handle((passage, err) -> {
                    if (err == null) {
                        return Optional.of(true);
                    }
                    Throwable cause = err instanceof CompletionException && err.getCause() != null
                            ? err.getCause() : err;
                    return cause instanceof PassageNotFound ? Optional.of(false) : Optional.empty();
                });
    }

    public String translationAbbrev() {
        return TRANSLATIONS.getOrDefault(bibleId, "");
    }

    private CompletableFuture<Http.Resp> get(String usfm) {
        String url = baseUrl + "/v1/bibles/" + bibleId + "/passages/"
                + URLEncoder.encode(usfm, StandardCharsets.UTF_8) + "?format=text";
        return http.get(url, Map.of("X-YVP-App-Key", appKey, "Accept", "application/json"), timeoutMs);
    }

    private static RuntimeException notFoundOrError(String ref, int status) {
        return status == 404 ? new PassageNotFound(ref)
                : new IllegalStateException("YouVersion HTTP " + status + " for " + ref);
    }

    /** LAM.3.22-23 → LAM.3.22-LAM.3.23 (alternate YouVersion range syntax). */
    static String expandedRange(String canonicalRef) {
        int dash = canonicalRef.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String head = canonicalRef.substring(0, dash);          // LAM.3.22
        String end = canonicalRef.substring(dash + 1);          // 23
        int lastDot = head.lastIndexOf('.');
        String bookChapter = head.substring(0, lastDot);        // LAM.3
        return head + "-" + bookChapter + "." + end;
    }

    /** Tolerant response parsing: content/text at root or under data. */
    Passage parse(String canonicalRef, String body) {
        JsonObject root = JsonUtil.firstJsonObject(body);
        if (root == null) {
            throw new IllegalStateException("Unparseable YouVersion response for " + canonicalRef);
        }
        JsonObject source = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : root;
        String text = JsonUtil.str(source, "content", JsonUtil.str(source, "text", null));
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("YouVersion response had no passage text for " + canonicalRef);
        }
        String display = JsonUtil.str(source, "reference",
                JsonUtil.str(source, "human_reference", canonicalRef));
        String copyright = JsonUtil.str(source, "copyright", "");
        return new Passage(canonicalRef, display, cleanText(text), translationAbbrev(), copyright);
    }

    /** Verbatim text, whitespace-normalized; defensive tag strip in case format=text leaks markup. */
    static String cleanText(String raw) {
        return raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
