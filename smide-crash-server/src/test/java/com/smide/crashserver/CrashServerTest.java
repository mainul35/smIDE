package com.smide.crashserver;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report server as an IDE and a dashboard use it. */
class CrashServerTest {

    private static final Gson GSON = new Gson();
    private static final String TOKEN = "test-token-1234";

    @TempDir
    Path data;

    private CrashServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = new CrashServer("127.0.0.1", 0, data, TOKEN);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void nothingUnderApiWithoutTheToken() throws Exception {
        assertEquals(401, post("/api/reports", report("aaaa1111", "x"), null).statusCode());
        assertEquals(401, post("/api/reports", report("aaaa1111", "x"), "wrong").statusCode());
        assertEquals(401, get("/api/failures", null).statusCode());
        // The health check and the dashboard's files are not data, and are open.
        assertEquals(200, get("/health", null).statusCode());
        assertEquals(200, get("/", null).statusCode());
    }

    @Test
    void reportsOfTheSameFailureAreGroupedAsOne() throws Exception {
        assertEquals(201, post("/api/reports", report("aaaa1111", "first"), TOKEN).statusCode());
        assertEquals(201, post("/api/reports", report("aaaa1111", "second"), TOKEN).statusCode());
        assertEquals(201, post("/api/reports", report("bbbb2222", "other"), TOKEN).statusCode());

        List<Map<String, Object>> open = failures("open", null);
        assertEquals(2, open.size());
        Map<String, Object> grouped = open.stream().filter(f -> f.get("signature").equals("aaaa1111")).findFirst().orElseThrow();
        assertEquals(2.0, grouped.get("occurrences"));
        assertEquals("second", grouped.get("message"), "what a failure looks like is taken from its latest report");
        assertEquals(1.0, grouped.get("notes"));
    }

    @Test
    void theSameReportSentTwiceIsKeptOnce() throws Exception {
        Map<String, Object> report = report("aaaa1111", "once");
        assertEquals(201, post("/api/reports", report, TOKEN).statusCode());
        HttpResponse<String> again = post("/api/reports", report, TOKEN);
        assertEquals(200, again.statusCode());
        assertTrue(again.body().contains("\"stored\":false"));
        assertEquals(1.0, failures("open", null).get(0).get("occurrences"));
    }

    @Test
    void triageMovesAFailureBetweenListsAndANewReportReopensAFixedOne() throws Exception {
        post("/api/reports", report("aaaa1111", "boom"), TOKEN);
        HttpResponse<String> fixed = post("/api/failures/aaaa1111/status",
                Map.of("status", "fixed", "comment", "Fixed in 7e701b1"), TOKEN);
        assertEquals(200, fixed.statusCode());
        assertEquals(0, failures("open", null).size());
        assertEquals("Fixed in 7e701b1", failures("fixed", null).get(0).get("comment"));

        HttpResponse<String> back = post("/api/reports", report("aaaa1111", "boom again"), TOKEN);
        assertTrue(back.body().contains("\"reopened\":true"), "a fixed failure that is reported again was not fixed");
        assertEquals(1, failures("open", null).size());
    }

    @Test
    void aStatusThatDoesNotExistIsRefused() throws Exception {
        post("/api/reports", report("aaaa1111", "boom"), TOKEN);
        assertEquals(400, post("/api/failures/aaaa1111/status", Map.of("status", "deleted"), TOKEN).statusCode());
        assertEquals(404, post("/api/failures/ffff9999/status", Map.of("status", "fixed"), TOKEN).statusCode());
    }

    @Test
    void aWholeReportCanBeReadBackWithItsNoteAndStack() throws Exception {
        Map<String, Object> report = report("aaaa1111", "boom");
        post("/api/reports", report, TOKEN);
        HttpResponse<String> read = get("/api/reports/" + report.get("id"), TOKEN);
        assertEquals(200, read.statusCode());
        Map<String, Object> back = GSON.fromJson(read.body(), new TypeToken<Map<String, Object>>() { }.getType());
        assertEquals("I pressed Run", back.get("note"));
        assertTrue(String.valueOf(back.get("stack")).contains("at com.smide.Example.run"));
        assertEquals(404, get("/api/reports/" + UUID.randomUUID(), TOKEN).statusCode());
    }

    @Test
    void searchFindsAFailureByItsMessage() throws Exception {
        post("/api/reports", report("aaaa1111", "cannot open the terminal"), TOKEN);
        post("/api/reports", report("bbbb2222", "gutter overflow"), TOKEN);
        List<Map<String, Object>> found = failures("all", "TERMINAL");
        assertEquals(1, found.size());
        assertEquals("aaaa1111", found.get(0).get("signature"));
    }

    @Test
    void anOversizedOrMalformedReportIsRefused() throws Exception {
        Map<String, Object> huge = report("aaaa1111", "x".repeat(CrashServer.MAX_BODY + 10));
        assertEquals(413, post("/api/reports", huge, TOKEN).statusCode());
        assertEquals(400, raw("/api/reports", "this is not json", TOKEN).statusCode());
        Map<String, Object> noSignature = report("aaaa1111", "x");
        noSignature.put("signature", "");
        assertEquals(400, post("/api/reports", noSignature, TOKEN).statusCode());
    }

    @Test
    void theDashboardIsServedWithAPolicyThatAllowsNoScriptButItsOwn() throws Exception {
        HttpResponse<String> page = get("/", null);
        assertTrue(page.body().contains("smIDE crash reports"));
        String policy = page.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(policy.contains("script-src 'self'"), policy);
        assertFalse(policy.contains("unsafe-inline"), policy);
        assertEquals(200, get("/app.js", null).statusCode());
        assertEquals(404, get("/../pom.xml", null).statusCode());
    }

    @Test
    void theSummaryCountsReportsAndFailuresByStatus() throws Exception {
        post("/api/reports", report("aaaa1111", "a"), TOKEN);
        post("/api/reports", report("aaaa1111", "a"), TOKEN);
        post("/api/reports", report("bbbb2222", "b"), TOKEN);
        post("/api/failures/bbbb2222/status", Map.of("status", "ignored"), TOKEN);
        Map<String, Object> summary = GSON.fromJson(get("/api/summary", TOKEN).body(),
                new TypeToken<Map<String, Object>>() { }.getType());
        assertEquals(3.0, summary.get("reports"));
        assertEquals(3.0, summary.get("lastDay"));
        @SuppressWarnings("unchecked")
        Map<String, Object> byStatus = (Map<String, Object>) summary.get("failures");
        assertEquals(1.0, byStatus.get("open"));
        assertEquals(1.0, byStatus.get("ignored"));
    }

    // ------------------------------------------------------------------ helpers

    /** A report shaped as smIDE's CrashReport sends one. */
    private static Map<String, Object> report(String signature, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", UUID.randomUUID().toString());
        r.put("kind", "exception");
        r.put("time", "2026-09-18T10:00:00Z");
        r.put("version", "0.1.0");
        r.put("os", "Linux 7.0.0 (amd64)");
        r.put("java", "21.0.12 (Eclipse Adoptium)");
        r.put("thread", "JavaFX Application Thread");
        r.put("exception", "java.lang.IllegalStateException");
        r.put("message", message);
        r.put("stack", "java.lang.IllegalStateException: " + message + "\n\tat com.smide.Example.run(Example.java:12)\n");
        r.put("signature", signature);
        r.put("where", "smIDE (Example)");
        r.put("note", "first".equals(message) ? "I pressed Run" : "once".equals(message) || "boom".equals(message) ? "I pressed Run" : "");
        r.put("extra", "");
        return r;
    }

    private List<Map<String, Object>> failures(String status, String q) throws Exception {
        String query = "?status=" + status + (q == null ? "" : "&q=" + java.net.URLEncoder.encode(q, "UTF-8"));
        HttpResponse<String> response = get("/api/failures" + query, TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        return GSON.fromJson(response.body(), new TypeToken<List<Map<String, Object>>>() { }.getType());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Object body, String token) throws Exception {
        return raw(path, GSON.toJson(body), token);
    }

    private HttpResponse<String> raw(String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
