package dev.minescripture.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Thin async wrapper over the JDK HttpClient (zero shaded deps). All calls are
 * off-thread by nature of sendAsync; callers bound them with orTimeout.
 */
public final class Http {

    public record Resp(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient client;

    public Http() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public Http(HttpClient client) {
        this.client = client;
    }

    public CompletableFuture<Resp> get(String url, Map<String, String> headers, long timeoutMs) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET();
        headers.forEach(b::header);
        return send(b.build());
    }

    public CompletableFuture<Resp> postJson(String url, Map<String, String> headers, String json, long timeoutMs) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        return send(b.build());
    }

    public CompletableFuture<Resp> postForm(String url, Map<String, String> headers, String form, long timeoutMs) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        headers.forEach(b::header);
        return send(b.build());
    }

    private CompletableFuture<Resp> send(HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> new Resp(r.statusCode(), r.body()));
    }
}
