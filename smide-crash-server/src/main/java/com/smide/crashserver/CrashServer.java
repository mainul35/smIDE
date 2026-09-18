package com.smide.crashserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The server smIDE's crash reports are sent to, and the dashboard they are read in.
 *
 * <pre>
 *   java -jar smide-crash-server.jar [--port 8787] [--host 0.0.0.0] [--data ./crash-data] [--token T]
 * </pre>
 *
 * <p>Everything under {@code /api} needs the token, as {@code Authorization: Bearer T}; the
 * IDE sends it with each report and the dashboard asks for it once. Without {@code --token}
 * (or {@code SMIDE_CRASH_TOKEN}) one is made on the first start and kept in the data
 * folder, so a restart does not lock out every IDE that was given it. A report carries
 * stack traces and file names, and a server that will take and show them to anybody who
 * finds the port is not one to run.
 *
 * <p>The dashboard is three static files. Report text is put on the page as text and never
 * as markup, and the page's content policy allows no script but its own file: a report is
 * whatever a client chose to send, and it is shown to whoever triages it.
 */
public final class CrashServer implements AutoCloseable {

    /** Larger than any real report; a stack trace of a deep recursion is a few hundred kilobytes. */
    static final int MAX_BODY = 1_048_576;

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern SIGNATURE = Pattern.compile("[A-Za-z0-9]{4,64}");

    /** Longest each field is kept at. A client may send anything; the database need not keep it all. */
    private static final Map<String, Integer> LIMITS = Map.ofEntries(
            Map.entry("id", 64), Map.entry("kind", 32), Map.entry("time", 64), Map.entry("version", 64),
            Map.entry("os", 300), Map.entry("java", 300), Map.entry("thread", 300),
            Map.entry("exception", 500), Map.entry("message", 20_000), Map.entry("stack", 400_000),
            Map.entry("signature", 64), Map.entry("where", 500), Map.entry("note", 20_000),
            Map.entry("extra", 200_000));

    private final HttpServer http;
    private final ReportStore store;
    private final byte[] token;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "crash-server");
        t.setDaemon(true);
        return t;
    });

    CrashServer(String host, int port, Path data, String token) throws IOException, SQLException {
        Files.createDirectories(data);
        this.store = new ReportStore(data);
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/", this::handle);
        http.setExecutor(workers);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        Path data = Path.of(options.getOrDefault("data", "crash-data"));
        String token = options.getOrDefault("token", System.getenv().getOrDefault("SMIDE_CRASH_TOKEN", ""));
        boolean made = false;
        if (token.isBlank()) {
            Path kept = data.resolve("token");
            if (Files.isRegularFile(kept)) {
                token = Files.readString(kept).strip();
            } else {
                Files.createDirectories(data);
                byte[] random = new byte[24];
                new SecureRandom().nextBytes(random);
                token = HexFormat.of().formatHex(random);
                Files.writeString(kept, token + "\n");
                made = true;
            }
        }
        int port = Integer.parseInt(options.getOrDefault("port", "8787"));
        String host = options.getOrDefault("host", "0.0.0.0");
        CrashServer server = new CrashServer(host, port, data, token);
        server.start();
        System.out.println("smIDE crash report server on http://" + host + ":" + server.port());
        System.out.println("Reports are kept in " + data.toAbsolutePath());
        if (made) {
            System.out.println("Made a token, kept in " + data.resolve("token").toAbsolutePath() + ":");
            System.out.println("  " + token);
            System.out.println("Give it to smIDE in Settings > Tools > Crash Reports, and to the dashboard.");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                out.put(args[i].substring(2), args[++i]);
            } else {
                throw new IllegalArgumentException("Expected --name value, got " + args[i]
                        + "\nusage: java -jar smide-crash-server.jar [--port 8787] [--host 0.0.0.0]"
                        + " [--data ./crash-data] [--token T]");
            }
        }
        return out;
    }

    void start() {
        http.start();
    }

    int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        http.stop(1);
        workers.shutdownNow();
        try {
            store.close();
        } catch (SQLException e) {
            System.err.println("Could not close the report store cleanly: " + e);
        }
    }

    // ------------------------------------------------------------------ routes

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/health")) {
                send(exchange, 200, "text/plain", "ok");
                return;
            }
            if (!path.startsWith("/api/")) {
                serveDashboard(exchange, path);
                return;
            }
            if (!authorised(exchange)) {
                json(exchange, 401, Map.of("error", "This needs the server's token, as Authorization: Bearer <token>."));
                return;
            }
            if (method.equals("POST") && path.equals("/api/reports")) {
                receive(exchange);
            } else if (method.equals("GET") && path.equals("/api/summary")) {
                json(exchange, 200, store.summary());
            } else if (method.equals("GET") && path.equals("/api/failures")) {
                Map<String, String> query = query(exchange);
                String status = query.get("status");
                json(exchange, 200, store.failures("all".equals(status) ? null : status, query.get("q")));
            } else if (method.equals("GET") && path.startsWith("/api/failures/")) {
                String signature = path.substring("/api/failures/".length());
                json(exchange, 200, store.reportsOf(signature));
            } else if (method.equals("POST") && path.startsWith("/api/failures/") && path.endsWith("/status")) {
                String signature = path.substring("/api/failures/".length(), path.length() - "/status".length());
                setStatus(exchange, signature);
            } else if (method.equals("GET") && path.startsWith("/api/reports/")) {
                Optional<Map<String, Object>> report = store.report(path.substring("/api/reports/".length()));
                if (report.isPresent()) {
                    json(exchange, 200, report.get());
                } else {
                    json(exchange, 404, Map.of("error", "No such report."));
                }
            } else {
                json(exchange, 404, Map.of("error", "No such endpoint: " + method + " " + path));
            }
        } catch (SQLException e) {
            System.err.println("Report store failed: " + e);
            json(exchange, 500, Map.of("error", "The report store failed; see the server's output."));
        } catch (RuntimeException e) {
            System.err.println("Request failed: " + e);
            json(exchange, 500, Map.of("error", "The request failed; see the server's output."));
        } finally {
            exchange.close();
        }
    }

    /** A report from an IDE: checked, trimmed, kept. Sent twice, it is kept once. */
    private void receive(HttpExchange exchange) throws IOException, SQLException {
        byte[] body = read(exchange);
        if (body == null) {
            json(exchange, 413, Map.of("error", "A report is at most " + MAX_BODY + " bytes."));
            return;
        }
        Map<String, Object> raw;
        try {
            raw = GSON.fromJson(new String(body, StandardCharsets.UTF_8), new TypeToken<Map<String, Object>>() { }.getType());
        } catch (JsonParseException e) {
            json(exchange, 400, Map.of("error", "The body is not JSON."));
            return;
        }
        if (raw == null) {
            json(exchange, 400, Map.of("error", "The body is empty."));
            return;
        }
        Map<String, String> report = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> field : LIMITS.entrySet()) {
            Object value = raw.get(field.getKey());
            String text = value == null ? "" : String.valueOf(value);
            report.put(field.getKey(), text.length() > field.getValue() ? text.substring(0, field.getValue()) : text);
        }
        if (!ID.matcher(report.get("id")).matches() || !SIGNATURE.matcher(report.get("signature")).matches()) {
            json(exchange, 400, Map.of("error", "A report needs an id and a signature."));
            return;
        }
        if (store.exists(report.get("id"))) {
            json(exchange, 200, Map.of("id", report.get("id"), "stored", false));
            return;
        }
        boolean reopened = store.add(report);
        json(exchange, 201, Map.of("id", report.get("id"), "stored", true, "reopened", reopened));
    }

    private void setStatus(HttpExchange exchange, String signature) throws IOException, SQLException {
        byte[] body = read(exchange);
        Map<String, Object> change = body == null ? null
                : GSON.fromJson(new String(body, StandardCharsets.UTF_8), new TypeToken<Map<String, Object>>() { }.getType());
        String status = change == null ? "" : String.valueOf(change.getOrDefault("status", ""));
        if (!ReportStore.STATUSES.contains(status)) {
            json(exchange, 400, Map.of("error", "The status is one of " + ReportStore.STATUSES + "."));
            return;
        }
        if (!store.knows(signature)) {
            json(exchange, 404, Map.of("error", "No such failure."));
            return;
        }
        Object comment = change.get("comment");
        store.setStatus(signature, status, comment == null ? null : String.valueOf(comment));
        json(exchange, 200, Map.of("signature", signature, "status", status));
    }

    /** The dashboard's three files, and nothing else from the class path. */
    private void serveDashboard(HttpExchange exchange, String path) throws IOException {
        String file = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/app.js" -> "app.js";
            case "/style.css" -> "style.css";
            default -> null;
        };
        if (file == null) {
            send(exchange, 404, "text/plain", "Not found");
            return;
        }
        try (InputStream in = CrashServer.class.getResourceAsStream("/dashboard/" + file)) {
            if (in == null) {
                send(exchange, 500, "text/plain", "The dashboard is missing from this build.");
                return;
            }
            String type = file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css" : "text/html";
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                            + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Compared in constant time, so the answer's timing says nothing about how much of a guess was right. */
    private boolean authorised(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] given = header.substring("Bearer ".length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token);
    }

    /** The body, or null when it is larger than a report can be. */
    private static byte[] read(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            return body.length > MAX_BODY ? null : body;
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, status, "application/json", GSON.toJson(body));
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
