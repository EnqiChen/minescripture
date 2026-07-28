package dev.minescripture.select;

import com.google.gson.JsonObject;
import dev.minescripture.util.Http;
import dev.minescripture.util.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * OAuth2 client-credentials token for Gloo AI Studio. Tokens live ~1 h; we
 * cache until 60 s before expiry and refresh on demand (plus GlooClient's
 * retry-once-on-401 forces a refresh).
 */
public final class GlooTokenManager {

    public static final String DEFAULT_BASE = "https://platform.ai.gloo.com";

    private final Http http;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;

    private volatile String token;
    private volatile long expiresAtMillis;

    public GlooTokenManager(Http http, String baseUrl, String clientId, String clientSecret) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public CompletableFuture<String> token() {
        String current = token;
        if (current != null && System.currentTimeMillis() < expiresAtMillis) {
            return CompletableFuture.completedFuture(current);
        }
        return refresh();
    }

    public CompletableFuture<String> refresh() {
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        return http.postForm(
                        baseUrl + "/oauth2/token",
                        Map.of("Authorization", "Basic " + basic),
                        "grant_type=client_credentials&scope=api%2Faccess",
                        10_000)
                .thenApply(resp -> {
                    if (!resp.ok()) {
                        throw new IllegalStateException("Gloo token request failed: HTTP " + resp.status());
                    }
                    JsonObject body = JsonUtil.firstJsonObject(resp.body());
                    if (body == null || !body.has("access_token")) {
                        throw new IllegalStateException("Gloo token response had no access_token");
                    }
                    String fresh = body.get("access_token").getAsString();
                    long expiresIn = JsonUtil.integer(body, "expires_in", 3600);
                    token = fresh;
                    expiresAtMillis = System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L;
                    return fresh;
                });
    }
}
