package com.smide.crash;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A crash report as a GitHub issue.
 *
 * <p>With a token, the issue is filed through GitHub's API - and a failure that already has
 * an open issue gets a comment on it rather than a second issue, found by the signature
 * every issue this writes carries in its body. A tracker with one issue per failure and a
 * count of comments on each is one somebody can work through; a tracker with an issue per
 * occurrence is one they stop reading.
 *
 * <p>Without a token, nothing is filed on anybody's behalf. GitHub's new-issue page is
 * opened in the browser with the report already written into it, for the reader to look
 * over and submit themselves.
 */
public final class GitHubIssues {

    public static final String DEFAULT_REPO = "mainul35/smIDE";
    public static final String DEFAULT_API = "https://api.github.com";

    /** GitHub refuses an issue body over 65,536 characters; this leaves room to spare. */
    private static final int MAX_BODY = 60_000;
    /** A pre-filled new-issue address is refused past roughly 8 KB; this stays under it. */
    private static final int MAX_URL = 7_500;

    private static final Gson GSON = new Gson();

    /** What happened when a report was taken to GitHub, and where to see it. */
    public record Result(Outcome outcome, String url, int number) {
    }

    public enum Outcome {
        /** A new issue was filed. */
        CREATED,
        /** The failure already had an open issue; the report was added to it as a comment. */
        COMMENTED,
        /** No token: the new-issue page is to be opened in the browser, filled in. */
        BROWSER
    }

    private final String repo;
    private final String token;
    private final String api;
    private final HttpClient client;

    public GitHubIssues(String repo, String token, String api) {
        this.repo = repo == null || repo.isBlank() ? DEFAULT_REPO : repo.strip();
        this.token = token == null ? "" : token.strip();
        String base = api == null || api.isBlank() ? DEFAULT_API : api.strip();
        this.api = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Files the report, or comments on its existing issue; or, with no token, says where to file it by hand. */
    public CompletableFuture<Result> file(CrashReport report) {
        if (token.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(Outcome.BROWSER, newIssueUrl(report), 0));
        }
        return findOpenIssue(report.signature()).thenCompose(existing -> {
            if (existing != null) {
                int number = existing.get("number").getAsInt();
                return post("/repos/" + repo + "/issues/" + number + "/comments", Map.of("body", comment(report)))
                        .thenApply(ignored -> new Result(Outcome.COMMENTED, existing.get("html_url").getAsString(), number));
            }
            return post("/repos/" + repo + "/issues", Map.of("title", title(report), "body", body(report)))
                    .thenApply(created -> new Result(Outcome.CREATED, created.get("html_url").getAsString(),
                            created.get("number").getAsInt()));
        });
    }

    /** Where an issue would go, and how, in one line for a tooltip. */
    public String describe() {
        return token.isEmpty()
                ? "Open a new issue in " + repo + " in your browser, with the report written in"
                : "File an issue in " + repo + ", or add to the one this failure already has";
    }

    /** Checks the repository can be reached with this token; completes with its full name. */
    public CompletableFuture<String> check() {
        return get("/repos/" + repo).thenApply(repository -> {
            JsonElement name = repository.get("full_name");
            return name == null ? repo : name.getAsString();
        });
    }

    // ------------------------------------------------------------------ writing

    static String title(CrashReport report) {
        String title = "[crash] " + report.headline();
        return title.length() > 120 ? title.substring(0, 117) + "..." : title;
    }

    /**
     * The issue as a person reads it on GitHub: what failed, a table of where and on what,
     * the note, the stack folded away, and the signature that later reports are matched by.
     */
    static String body(CrashReport report) {
        StringBuilder out = new StringBuilder();
        out.append("**smIDE ").append(report.version()).append("** ")
                .append(CrashReport.PREVIOUS_SESSION.equals(report.kind()) ? "stopped: the Java runtime itself failed."
                        : CrashReport.STARTUP.equals(report.kind()) ? "could not start."
                        : "hit an unexpected error.")
                .append("\n\n");
        out.append(facts(report));
        if (!report.note().isEmpty()) {
            out.append("\n**What they were doing:** ").append(report.note().replace("\n", " ")).append('\n');
        }
        String trace = report.stack().isEmpty() ? report.extra() : report.stack();
        if (!trace.isEmpty()) {
            out.append("\n<details><summary>").append(report.stack().isEmpty() ? "Runtime crash log" : "Stack trace")
                    .append("</summary>\n\n").append(fenced(trace)).append("\n</details>\n");
        }
        out.append(signatureLine(report));
        return fit(out.toString(), report);
    }

    /** A later occurrence of a failure that already has an issue. */
    static String comment(CrashReport report) {
        StringBuilder out = new StringBuilder("Seen again.\n\n").append(facts(report));
        if (!report.note().isEmpty()) {
            out.append("\n**What they were doing:** ").append(report.note().replace("\n", " ")).append('\n');
        }
        if (!report.stack().isEmpty()) {
            out.append("\n<details><summary>Stack trace</summary>\n\n").append(fenced(report.stack()))
                    .append("\n</details>\n");
        }
        return fit(out.toString(), report);
    }

    private static String facts(CrashReport report) {
        StringBuilder out = new StringBuilder("| | |\n|---|---|\n");
        row(out, "Error", report.headline());
        row(out, "Where", report.where());
        row(out, "When", report.time());
        row(out, "System", report.os());
        row(out, "Java", report.java());
        if (!report.thread().isEmpty()) {
            row(out, "Thread", report.thread());
        }
        return out.toString();
    }

    private static void row(StringBuilder out, String name, String value) {
        // A pipe ends a table cell and a line break ends the table: neither may come through as itself.
        out.append("| ").append(name).append(" | ")
                .append(value.replace("\\", "\\\\").replace("|", "\\|").replace("\r", "").replace("\n", " "))
                .append(" |\n");
    }

    /**
     * Text in a code block that its own backticks cannot close: the fence is made one
     * backtick longer than the longest run in the text.
     */
    private static String fenced(String text) {
        int longest = 0;
        int run = 0;
        for (char c : text.toCharArray()) {
            run = c == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + "text\n" + text.stripTrailing() + "\n" + fence;
    }

    private static String signatureLine(CrashReport report) {
        return "\n<sub>Crash signature: `" + report.signature() + "` - later reports of the same failure"
                + " are added here as comments.</sub>\n";
    }

    /** Short enough for GitHub, cut from the stack trace, which is where the length is. */
    private static String fit(String text, CrashReport report) {
        if (text.length() <= MAX_BODY) {
            return text;
        }
        return text.substring(0, MAX_BODY - 400) + "\n```\n\n(Cut short: the whole report is saved on the"
                + " machine it came from.)\n" + signatureLine(report);
    }

    /**
     * GitHub's new-issue page with the report written in, for filing by hand.
     *
     * <p>Addresses have a length past which they are refused, so the stack trace is cut to
     * its first frames when the whole of it will not fit - they are the ones that say where
     * it happened - and the reader is told to attach the saved report for the rest.
     */
    String newIssueUrl(CrashReport report) {
        String web = DEFAULT_API.equals(api) ? "https://github.com" : api.replaceFirst("/api/v3$", "");
        String base = web + "/" + repo + "/issues/new?title=" + encode(title(report)) + "&body=";
        String body = body(report);
        for (int frames = 40; base.length() + encode(body).length() > MAX_URL && frames >= 0; frames -= 5) {
            body = shortened(report, frames);
        }
        return base + encode(body);
    }

    private static String shortened(CrashReport report, int frames) {
        String[] lines = (report.stack().isEmpty() ? report.extra() : report.stack()).split("\n");
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, frames); i++) {
            trace.append(lines[i]).append('\n');
        }
        if (lines.length > frames) {
            trace.append("... ").append(lines.length - frames).append(" more lines\n");
        }
        StringBuilder out = new StringBuilder();
        out.append("**smIDE ").append(report.version()).append("** hit an unexpected error.\n\n").append(facts(report));
        if (!report.note().isEmpty()) {
            out.append("\n**What they were doing:** ").append(report.note().replace("\n", " ")).append('\n');
        }
        out.append("\n").append(fenced(trace.toString()))
                .append("\n\nThe whole report is too long for this page. It is saved in ~/.smide/logs/crashes;"
                        + " please attach it.\n")
                .append(signatureLine(report));
        return out.toString();
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ------------------------------------------------------------------ talking to GitHub

    /**
     * The open issue this failure already has, found by its signature; null when there is none.
     *
     * <p>GitHub's search catches up with a new issue after a short while, so two reports of
     * a brand-new failure a few seconds apart can still make two issues. That is the rare
     * case, and a duplicate is easy to close.
     */
    private CompletableFuture<JsonObject> findOpenIssue(String signature) {
        String query = "repo:" + repo + " is:issue is:open in:body \"" + signature + "\"";
        return get("/search/issues?q=" + encode(query)).thenApply(result -> {
            JsonArray items = result.getAsJsonArray("items");
            return items == null || items.isEmpty() ? null : items.get(0).getAsJsonObject();
        });
    }

    private CompletableFuture<JsonObject> get(String path) {
        return send(request(path).GET().build());
    }

    private CompletableFuture<JsonObject> post(String path, Map<String, String> body) {
        return send(request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(api + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "smIDE-crash-reporter");
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private CompletableFuture<JsonObject> send(HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(explain(response.statusCode(), response.body()));
            }
            return GSON.fromJson(response.body(), JsonObject.class);
        });
    }

    /** GitHub's refusals in words that say what to do about them. */
    private String explain(int status, String body) {
        String message = "";
        try {
            JsonObject parsed = GSON.fromJson(body, JsonObject.class);
            if (parsed != null && parsed.has("message")) {
                message = parsed.get("message").getAsString();
            }
        } catch (RuntimeException e) {
            // Not JSON: the status says enough.
        }
        return switch (status) {
            case 401 -> "GitHub did not accept the token. It may have expired or been revoked.";
            case 403 -> "The token may not create issues in " + repo + ". It needs the Issues permission"
                    + (message.isEmpty() ? "." : " (GitHub says: " + message + ").");
            case 404 -> "GitHub has no repository " + repo + " that this token can see.";
            case 410 -> "Issues are turned off for " + repo + ".";
            default -> "GitHub answered " + status + (message.isEmpty() ? "." : ": " + message);
        };
    }
}
