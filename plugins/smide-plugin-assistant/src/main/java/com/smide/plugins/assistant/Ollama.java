package com.smide.plugins.assistant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mdviewer.ai.AiConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Asking Ollama what it has, in its own words.
 *
 * <p>Everything else here speaks the OpenAI shape, and so does Ollama when it is addressed at
 * {@code /v1} - which is why the assistant works against a local one. Its own API does not:
 * {@code /api/tags} answers {@code {"models":[{"name":"gemma4:31b"}]}}, and an address typed
 * without the {@code /v1} on the end - which is the address Ollama's own documentation gives -
 * lists nothing at all. The reader sees a dropdown with one stale name in it and no way to find
 * out what is actually installed.
 *
 * <p>So when the ordinary way comes back empty, this asks the other way before giving up. Only
 * then: an endpoint that answered properly is never asked twice, and a host that was not allowed
 * to receive code is not quietly contacted here either.
 */
final class Ollama {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private Ollama() {
    }

    /** What a local Ollama says it has, or nothing at all. */
    static List<String> models(AiConfig.Endpoint endpoint, AiConfig config) {
        String base = endpoint == null ? "" : endpoint.baseUrl();
        if (base == null || base.isBlank() || !config.isAllowed(base)) {
            return List.of();
        }
        String root = base.strip();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.endsWith("/v1")) {
            root = root.substring(0, root.length() - "/v1".length());
        }
        return namesIn(get(root + "/api/tags", endpoint.apiKey()));
    }

    private static String get(String url, String key) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PATIENCE)
                    .header("Accept", "application/json")
                    .GET();
            if (key != null && !key.isBlank()) {
                request.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(PATIENCE)
                    .build()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2 ? response.body() : "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (RuntimeException | java.io.IOException e) {
            // Nothing there, or nothing that answers: the caller shows what it already had.
            return "";
        }
    }

    /** The names out of {@code {"models":[{"name":"..."}]}}, in the order given. */
    static List<String> namesIn(String json) {
        List<String> names = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return names;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return names;
            }
            JsonObject body = parsed.getAsJsonObject();
            if (!body.has("models") || !body.get("models").isJsonArray()) {
                return names;
            }
            JsonArray models = body.getAsJsonArray("models");
            for (JsonElement element : models) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject model = element.getAsJsonObject();
                // "name" is what /api/tags calls it; "model" is what newer builds add beside it.
                for (String field : List.of("name", "model")) {
                    if (model.has(field) && model.get(field).isJsonPrimitive()) {
                        String name = model.get(field).getAsString();
                        if (!name.isBlank() && !names.contains(name)) {
                            names.add(name);
                        }
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return names;
    }
}
