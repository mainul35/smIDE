package com.smide.plugins.assistant;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What the agent can find out that is not in the project and not in the model.
 *
 * <p>Two things, and the difference between them matters. Fetching a page the developer or the
 * project names - a stack trace's link, a library's documentation - needs nothing but the address
 * and works everywhere. Searching needs a search engine, and the ones that answer without a key
 * are the ones that change their markup without notice and block whatever they take for a robot;
 * an IDE that quietly starts giving worse answers because a page changed is worse than one that
 * says it cannot search.
 *
 * <p>So searching is done through a search API with a key, named in {@code ~/.smide/ai.properties}
 * as {@code search.provider} and {@code search.key}, and when there is no key the agent is told
 * outright that it has no search - at which point it asks the developer for a link, or answers
 * from what it has. Tavily and Brave are both understood; both have a free allowance.
 */
public final class AskWeb {

    private static final int PAGE_CHARS = 20_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    private final String provider;
    private final String key;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public AskWeb(String provider, String key) {
        this.provider = provider == null ? "" : provider.strip().toLowerCase(java.util.Locale.ROOT);
        this.key = key == null ? "" : key.strip();
    }

    public boolean canSearch() {
        return !key.isBlank() && (provider.equals("tavily") || provider.equals("brave"));
    }

    /** Why searching is not on, in words the model can pass on to the developer. */
    public String whyNotSearching() {
        if (key.isBlank()) {
            return "Web search is not set up: no search key in ~/.smide/ai.properties"
                    + " (search.provider=tavily|brave and search.key=...). You can still be given a URL to read.";
        }
        return "Web search is not set up: search.provider must be tavily or brave.";
    }

    /** What the web says about something, as a few results with their own words under them. */
    public String search(String query) {
        if (!canSearch()) {
            return whyNotSearching();
        }
        try {
            return provider.equals("tavily") ? tavily(query) : brave(query);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "The search did not go through: " + e.getMessage();
        }
    }

    private String tavily(String query) throws IOException, InterruptedException {
        String body = "{\"api_key\":" + quote(key) + ",\"query\":" + quote(query)
                + ",\"max_results\":5,\"include_answer\":true}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            return "The search engine answered " + response.statusCode() + ".";
        }
        return readResults(response.body());
    }

    private String brave(String query) throws IOException, InterruptedException {
        String url = "https://api.search.brave.com/res/v1/web/search?count=5&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", key)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            return "The search engine answered " + response.statusCode() + ".";
        }
        return readResults(response.body());
    }

    /**
     * The titles, addresses and summaries out of whatever the engine sent.
     *
     * <p>Read by name rather than by shape: the two engines disagree about where the results live
     * - {@code results} against {@code web.results} - and agree about what one is called.
     */
    static String readResults(String json) {
        com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(json);
        List<String> out = new ArrayList<>();
        collect(parsed, out);
        return out.isEmpty() ? "The search came back with nothing." : String.join("\n\n", out);
    }

    private static void collect(com.google.gson.JsonElement element, List<String> out) {
        if (out.size() >= 6) {
            return;
        }
        if (element.isJsonArray()) {
            for (com.google.gson.JsonElement each : element.getAsJsonArray()) {
                collect(each, out);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        com.google.gson.JsonObject object = element.getAsJsonObject();
        String url = text(object, "url");
        String title = text(object, "title");
        if (!url.isBlank()) {
            String summary = text(object, "content");
            if (summary.isBlank()) {
                summary = text(object, "description");
            }
            if (summary.isBlank()) {
                summary = text(object, "snippet");
            }
            out.add((title.isBlank() ? url : title) + "\n" + url
                    + (summary.isBlank() ? "" : "\n" + trim(summary, 700)));
            return;
        }
        for (String key : List.of("results", "web", "mixed", "answer")) {
            if (object.has(key)) {
                collect(object.get(key), out);
            }
        }
    }

    private static String text(com.google.gson.JsonObject object, String name) {
        com.google.gson.JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    /** One page, as text: the markup taken out, because the model is reading it, not rendering it. */
    public String fetch(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "That is not a web address: " + url;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "smIDE assistant")
                    .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return url + " answered " + response.statusCode() + ".";
            }
            return trim(stripMarkup(response.body()), PAGE_CHARS);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Could not read " + url + ": " + e.getMessage();
        }
    }

    /** The words out of a page: scripts and styles dropped, tags removed, entities put back. */
    static String stripMarkup(String html) {
        String text = html.replaceAll("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String trim(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "\n... (the rest left out)";
    }

    private static String quote(String value) {
        return new com.google.gson.Gson().toJson(value);
    }
}
