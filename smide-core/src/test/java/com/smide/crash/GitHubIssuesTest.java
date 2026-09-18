package com.smide.crash;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crash reports taken to GitHub, against a small stand-in for GitHub's API. */
class GitHubIssuesTest {

    private static final Gson GSON = new Gson();

    /** What the stand-in was asked, in order: "METHOD path", and the bodies posted. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<JsonObject> posted = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> agents = new CopyOnWriteArrayList<>();
    /** The issue a search finds, or null when the search finds none. */
    private volatile String existing;
    /** A status to refuse every request with, or 0. */
    private volatile int refuse;

    private HttpServer github;
    private String api;

    @BeforeEach
    void start() throws IOException {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/", this::answer);
        github.start();
        api = "http://127.0.0.1:" + github.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        github.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        calls.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + URLDecoder.decode(query, StandardCharsets.UTF_8)));
        authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        agents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            posted.add(GSON.fromJson(body, JsonObject.class));
        }
        String reply;
        int status;
        if (refuse != 0) {
            status = refuse;
            reply = "{\"message\":\"Bad credentials\"}";
        } else if (path.equals("/search/issues")) {
            status = 200;
            reply = existing == null ? "{\"items\":[]}"
                    : "{\"items\":[{\"number\":12,\"html_url\":\"" + existing + "\"}]}";
        } else if (path.equals("/repos/owner/repo/issues")) {
            status = 201;
            reply = "{\"number\":41,\"html_url\":\"https://github.com/owner/repo/issues/41\"}";
        } else if (path.equals("/repos/owner/repo/issues/12/comments")) {
            status = 201;
            reply = "{\"id\":9}";
        } else if (path.equals("/repos/owner/repo")) {
            status = 200;
            reply = "{\"full_name\":\"owner/repo\"}";
        } else {
            status = 404;
            reply = "{\"message\":\"Not Found\"}";
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static CrashReport report(String message) {
        return CrashReport.of(CrashReport.EXCEPTION, failure(message), "JavaFX Application Thread", "0.1.0")
                .withNote("I pressed Run");
    }

    private static Throwable failure(String message) {
        IllegalStateException e = new IllegalStateException(message);
        e.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.smide.plugins.git.GitService", "commit", "GitService.java", 214),
                new StackTraceElement("java.lang.Thread", "run", "Thread.java", 1583)});
        return e;
    }

    @Test
    void aNewFailureBecomesANewIssue() throws Exception {
        CrashReport report = report("Repository is locked");
        GitHubIssues.Result result = new GitHubIssues("owner/repo", "tok", api).file(report).get();

        assertEquals(GitHubIssues.Outcome.CREATED, result.outcome());
        assertEquals(41, result.number());
        assertEquals("https://github.com/owner/repo/issues/41", result.url());
        assertTrue(calls.get(0).startsWith("GET /search/issues?q=repo:owner/repo is:issue is:open in:body \""
                + report.signature() + "\""), calls.get(0));
        assertEquals("POST /repos/owner/repo/issues", calls.get(1));
        JsonObject issue = posted.get(0);
        assertEquals("[crash] IllegalStateException: Repository is locked", issue.get("title").getAsString());
        assertTrue(issue.get("body").getAsString().contains(report.signature()),
                "later reports find the issue by the signature in its body");
        assertTrue(issue.get("body").getAsString().contains("I pressed Run"));
        assertEquals("Bearer tok", authorizations.get(0));
        assertEquals("smIDE-crash-reporter", agents.get(0), "GitHub refuses requests without a User-Agent");
    }

    @Test
    void aFailureThatAlreadyHasAnIssueIsAddedToIt() throws Exception {
        existing = "https://github.com/owner/repo/issues/12";
        GitHubIssues.Result result = new GitHubIssues("owner/repo", "tok", api).file(report("Repository is locked")).get();

        assertEquals(GitHubIssues.Outcome.COMMENTED, result.outcome());
        assertEquals(12, result.number());
        assertEquals("POST /repos/owner/repo/issues/12/comments", calls.get(1));
        assertEquals(2, calls.size(), "no second issue is made");
        assertTrue(posted.get(0).get("body").getAsString().startsWith("Seen again."));
    }

    @Test
    void refusalsAreSaidInWordsThatSayWhatToDo() {
        refuse = 401;
        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> new GitHubIssues("owner/repo", "expired", api).file(report("x")).get());
        assertTrue(failed.getCause().getMessage().contains("did not accept the token"), failed.getCause().getMessage());

        refuse = 404;
        failed = assertThrows(ExecutionException.class,
                () -> new GitHubIssues("owner/repo", "tok", api).file(report("x")).get());
        assertTrue(failed.getCause().getMessage().contains("no repository owner/repo"), failed.getCause().getMessage());
    }

    @Test
    void withoutATokenNothingIsSentAndTheIssueIsWrittenIntoTheBrowser() throws Exception {
        CrashReport report = report("Repository is locked");
        GitHubIssues.Result result = new GitHubIssues("owner/repo", "", GitHubIssues.DEFAULT_API).file(report).get();

        assertEquals(GitHubIssues.Outcome.BROWSER, result.outcome());
        assertTrue(result.url().startsWith("https://github.com/owner/repo/issues/new?title="), result.url());
        String decoded = URLDecoder.decode(result.url(), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("[crash] IllegalStateException: Repository is locked"));
        assertTrue(decoded.contains(report.signature()));
        assertTrue(calls.isEmpty(), "nothing was sent anywhere");
    }

    @Test
    void anEnormousReportStillFitsInTheAddressABrowserWillOpen() throws Exception {
        StringBuilder deep = new StringBuilder();
        StackTraceElement[] frames = new StackTraceElement[3000];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("com.smide.editor.Deep", "recurse" + (i % 7), "Deep.java", i);
        }
        StackOverflowError overflow = new StackOverflowError();
        overflow.setStackTrace(frames);
        CrashReport report = CrashReport.of(CrashReport.EXCEPTION, overflow, "main", "0.1.0");
        String url = new GitHubIssues("owner/repo", "", GitHubIssues.DEFAULT_API).file(report).get().url();

        assertTrue(url.length() <= 7_500, "was " + url.length());
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("please attach it"), "the reader is told the rest is saved");
        assertTrue(decoded.contains(report.signature()), "and it can still be matched to later reports");
    }

    @Test
    void textFromTheReportCannotBreakTheIssuesFormatting() {
        CrashReport tricky = CrashReport.of(CrashReport.EXCEPTION,
                failure("a | b\nnext line ``` not the end of the block"), "main", "0.1.0");
        String body = GitHubIssues.body(tricky);

        // The headline is in a table cell, where a pipe would end the cell: it is escaped.
        assertTrue(body.contains("| Error | IllegalStateException: a \\| b |"), body);
        // The stack contains three backticks, so the block around it is fenced with four.
        assertTrue(body.contains("````text\n"), body);
        assertFalse(body.contains("\n```text\n"), body);
    }

    @Test
    void accessIsCheckedAgainstTheRepositoryItself() throws Exception {
        assertEquals("owner/repo", new GitHubIssues("owner/repo", "tok", api).check().get());
        assertEquals("GET /repos/owner/repo", calls.get(0));
    }
}
