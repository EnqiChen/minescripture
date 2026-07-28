package dev.minescripture.select;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.util.Http;
import dev.minescripture.util.JsonUtil;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Gloo AI Studio chat-completions client: OpenAI shape + auto_routing +
 * tradition. Returns the assistant message content; the routed model is
 * logged server-side only. Retries exactly once on 401 with a fresh token.
 */
public final class GlooClient {

    public static final String DEFAULT_BASE = "https://platform.ai.gloo.com";

    private final Http http;
    private final String baseUrl;
    private final GlooTokenManager tokens;
    private final MineScriptureConfig config;
    private final Consumer<String> log;

    public GlooClient(Http http, String baseUrl, GlooTokenManager tokens,
                      MineScriptureConfig config, Consumer<String> log) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.tokens = tokens;
        this.config = config;
        this.log = log;
    }

    public CompletableFuture<String> complete(String systemMessage, String userMessage) {
        String body = buildBody(systemMessage, userMessage);
        return tokens.token()
                .thenCompose(token -> post(token, body))
                .thenCompose(resp -> {
                    if (resp.status() == 401) {
                        return tokens.refresh()
                                .thenCompose(fresh -> post(fresh, body))
                                .thenApply(this::content);
                    }
                    return CompletableFuture.completedFuture(content(resp));
                });
    }

    private CompletableFuture<Http.Resp> post(String token, String body) {
        return http.postJson(
                baseUrl + "/ai/v2/chat/completions",
                Map.of("Authorization", "Bearer " + token),
                body,
                config.timeoutBackgroundMs);
    }

    private String buildBody(String systemMessage, String userMessage) {
        JsonObject root = new JsonObject();
        root.addProperty("auto_routing", true);
        root.addProperty("tradition", config.tradition);
        root.addProperty("temperature", config.temperature);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemMessage));
        messages.add(message("user", userMessage));
        root.add("messages", messages);
        return JsonUtil.GSON.toJson(root);
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private String content(Http.Resp resp) {
        if (!resp.ok()) {
            throw new IllegalStateException("Gloo completion failed: HTTP " + resp.status());
        }
        JsonObject root = JsonUtil.firstJsonObject(resp.body());
        if (root == null) {
            throw new IllegalStateException("Gloo completion returned no JSON");
        }
        if (root.has("model")) {
            log.accept("Gloo routed model: " + root.get("model").getAsString());
        }
        JsonArray choices = JsonUtil.array(root, "choices");
        if (choices.isEmpty()) {
            throw new IllegalStateException("Gloo completion had no choices");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new IllegalStateException("Gloo completion had no message content");
        }
        return message.get("content").getAsString();
    }
}
