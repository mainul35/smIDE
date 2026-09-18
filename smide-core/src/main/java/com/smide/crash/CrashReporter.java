package com.smide.crash;

import com.smide.api.settings.Settings;
import javafx.application.Platform;
import javafx.stage.Window;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Catches what nothing else caught, writes it down, and asks the reader whether to send it.
 *
 * <p>Three kinds of failure, found three ways:
 *
 * <ul>
 *   <li>An exception nothing handled, on any thread - including the one drawing the window,
 *       where it used to be printed to a terminal that a desktop launch does not have, and
 *       the reader saw only a button that did nothing. Caught by the default handler this
 *       installs.
 *   <li>A failure to start at all, handed over by {@code SmIdeApp}.
 *   <li>The Java runtime itself dying - a native crash, which no Java code survives to
 *       report. That is found at the next start: a marker is written while smIDE runs and
 *       removed when it closes properly, so a marker left behind by a process that is gone
 *       means it did not close properly, and the runtime's own crash log, if it left one,
 *       says why.
 * </ul>
 *
 * <p>Nothing is sent without being asked. Every report is saved under
 * {@code ~/.smide/logs/crashes}; the dialog shows exactly what would be sent; and it is only
 * sent when the reader presses Send, to a server they configured themselves.
 */
public final class CrashReporter {

    /** Settings: the report server's address, and the token it expects. */
    public static final String SERVER_KEY = "crash.server";
    public static final String TOKEN_KEY = "crash.token";
    /** Settings: whether to show the dialog at all; the report is saved either way. */
    public static final String DIALOG_KEY = "crash.dialog";
    /** Settings: the GitHub repository issues are filed in, a token to file them with, and the API. */
    public static final String GITHUB_REPO_KEY = "crash.github.repo";
    public static final String GITHUB_TOKEN_KEY = "crash.github.token";
    public static final String GITHUB_API_KEY = "crash.github.api";

    /** What the reader chose in the dialog. */
    public enum Choice {
        /** Carry on: the IDE is still running. */
        CLOSED,
        /** Start smIDE again. */
        RESTART,
        /** Start smIDE again without plugins or the last session. */
        RESTART_SAFE,
        /** Stop: only offered when smIDE could not start. */
        QUIT
    }

    /** Reports kept on disk; the oldest go first. */
    private static final int KEEP = 100;
    /** How much of the Java runtime's crash log goes into a report. */
    private static final int EXTRA_LINES = 80;

    private static volatile CrashReporter installed;

    private final Path crashes;
    private final Path marker;
    private final String version;
    private final Set<String> shownThisSession = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dialogOpen = new AtomicBoolean();
    private final ThreadLocal<Boolean> reporting = ThreadLocal.withInitial(() -> false);

    private volatile Settings settings;
    private volatile Supplier<Window> owner = () -> null;
    private volatile Consumer<Window> styler = w -> { };
    private volatile CrashReport pending;
    /** How the running IDE restarts itself, safely or not; null until there is an IDE. */
    private volatile Consumer<Boolean> restarter;
    /** How the running IDE opens an address; null until there is one. */
    private volatile Consumer<String> browser;

    private CrashReporter(Path homeDir, String version) {
        this.crashes = homeDir.resolve("logs").resolve("crashes");
        this.marker = homeDir.resolve("running");
        this.version = version;
    }

    /**
     * Installs the handler for every thread, and looks for a previous session that did not
     * end properly. Safe to call once, as early as possible; later calls return the first.
     */
    public static synchronized CrashReporter install(Path homeDir, String version) {
        if (installed != null) {
            return installed;
        }
        CrashReporter reporter = new CrashReporter(homeDir, version);
        reporter.pending = reporter.previousSession().orElse(null);
        reporter.markRunning();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> reporter.uncaught(thread, failure));
        installed = reporter;
        return reporter;
    }

    /** The reporter installed in this process, if there is one. */
    public static Optional<CrashReporter> installed() {
        return Optional.ofNullable(installed);
    }

    /**
     * What the dialog needs from the running IDE: its settings, the window to sit in front
     * of, and how to style a new window. Before these are given, the dialog still appears -
     * unowned and in JavaFX's own colours - because a failure during startup is exactly
     * when there is no IDE to ask.
     */
    public void attach(Settings settings, Supplier<Window> owner, Consumer<Window> styler) {
        this.settings = settings;
        this.owner = owner == null ? () -> null : owner;
        this.styler = styler == null ? w -> { } : styler;
    }

    /**
     * How the running IDE restarts itself - closing its editors the usual way first, so
     * nothing unsaved is lost - and how it opens an address. Only a supervised IDE can be
     * restarted, because only then is there something outside it to start it again.
     */
    public void onRestart(Consumer<Boolean> restarter, Consumer<String> browser) {
        this.restarter = restarter;
        this.browser = browser;
    }

    /** Whether the dialog may offer to restart the running IDE. */
    boolean canRestart() {
        return restarter != null && com.smide.Supervisor.supervised();
    }

    /** Whether smIDE is supervised, so that a failed start can be tried again. */
    boolean canRetryStart() {
        return com.smide.Supervisor.supervised();
    }

    /** Restarts the running IDE; the dialog calls this once it has closed. */
    void restart(boolean safe) {
        Consumer<Boolean> r = restarter;
        if (r != null) {
            r.accept(safe);
        }
    }

    /** Shows the report left by a session that died, once the window is there to show it in. */
    public void showPending() {
        CrashReport report = pending;
        pending = null;
        if (report != null) {
            Platform.runLater(() -> show(report));
        }
    }

    /** smIDE is closing properly: the next start has nothing to report. */
    public void closedCleanly() {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException | RuntimeException e) {
            // A marker that cannot be removed costs one false report next time; nothing more.
        }
    }

    // ------------------------------------------------------------- reporting

    /** The default handler: every thread's last resort. */
    void uncaught(Thread thread, Throwable failure) {
        // Printed first, as the JVM would have: a terminal, when there is one, still gets it.
        System.err.println("Exception in thread \"" + thread.getName() + "\"");
        failure.printStackTrace();
        report(CrashReport.EXCEPTION, failure, thread.getName(), true);
    }

    /**
     * Saves a report of this failure and offers it to the reader.
     *
     * <p>Guarded against itself: a failure while reporting a failure is printed and dropped,
     * because the alternative is a loop that fills the disk with reports of the reporter.
     */
    public CrashReport report(String kind, Throwable failure, String thread, boolean offer) {
        if (reporting.get()) {
            return null;
        }
        reporting.set(true);
        try {
            CrashReport report = CrashReport.of(kind, failure, thread, version);
            save(report);
            if (offer) {
                offer(report);
            }
            return report;
        } catch (Throwable t) {
            System.err.println("smIDE: could not report a failure: " + t);
            return null;
        } finally {
            reporting.set(false);
        }
    }

    /**
     * Shows the dialog for a report - once per kind of failure per session.
     *
     * <p>A failure that happens on every mouse movement would otherwise open a dialog on
     * every mouse movement, and one that fires while a dialog is up would stack dialogs on
     * top of it. Every occurrence is still saved; the reader is asked about each distinct
     * failure once.
     */
    private void offer(CrashReport report) {
        if (settings != null && !settings.getBoolean(DIALOG_KEY, true)) {
            return;
        }
        if (!shownThisSession.add(report.signature())) {
            return;
        }
        if (!toolkitRunning()) {
            return;
        }
        Platform.runLater(() -> show(report));
    }

    /**
     * Shows the dialog now, on this thread, which must be the JavaFX one, and does what the
     * reader chose in it. Returns the choice; null when the dialog could not be shown.
     */
    public Choice show(CrashReport report) {
        if (!dialogOpen.compareAndSet(false, true)) {
            return Choice.CLOSED;
        }
        Choice choice;
        try {
            choice = new CrashDialog(this, report, owner.get(), styler).showAndWait();
        } catch (Throwable t) {
            System.err.println("smIDE: could not show the crash dialog: " + t);
            return null;
        } finally {
            dialogOpen.set(false);
        }
        // A failed start decides for itself what to do with the choice; a running IDE is restarted here.
        if (!CrashReport.STARTUP.equals(report.kind())) {
            if (choice == Choice.RESTART) {
                restart(false);
            } else if (choice == Choice.RESTART_SAFE) {
                restart(true);
            }
        }
        return choice;
    }

    private static boolean toolkitRunning() {
        try {
            Platform.runLater(() -> { });
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ disk

    /** Where the reports are kept. */
    public Path folder() {
        return crashes;
    }

    Path fileOf(CrashReport report) {
        return crashes.resolve(report.time().replace(':', '-') + "-" + report.signature() + ".json");
    }

    void save(CrashReport report) {
        try {
            Files.createDirectories(crashes);
            Files.writeString(fileOf(report), report.json(), StandardCharsets.UTF_8);
            prune();
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: could not save a crash report: " + e);
        }
    }

    private void prune() throws IOException {
        List<Path> reports;
        try (Stream<Path> files = Files.list(crashes)) {
            reports = new ArrayList<>(files.filter(p -> p.getFileName().toString().endsWith(".json")).toList());
        }
        if (reports.size() <= KEEP) {
            return;
        }
        reports.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path old : reports.subList(0, reports.size() - KEEP)) {
            Files.deleteIfExists(old);
        }
    }

    // ------------------------------------------------------------------ GitHub

    /** Where issues go, as the settings say; the defaults when there is nothing set. */
    public GitHubIssues github() {
        Settings s = settings;
        return new GitHubIssues(
                s == null ? "" : s.get(GITHUB_REPO_KEY, ""),
                s == null ? "" : s.get(GITHUB_TOKEN_KEY, ""),
                s == null ? "" : s.get(GITHUB_API_KEY, ""));
    }

    /** Opens an address in the reader's browser: the IDE's way when there is an IDE, the desktop's otherwise. */
    void browse(String url) {
        Consumer<String> b = browser;
        if (b != null) {
            b.accept(url);
            return;
        }
        Thread opener = new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(URI.create(url));
                }
            } catch (Exception e) {
                System.err.println("smIDE: cannot open " + url + ": " + e);
            }
        }, "smide-browse");
        opener.setDaemon(true);
        opener.start();
    }

    // ------------------------------------------------------------------ sending

    /** The report server, if one is configured. */
    public Optional<String> server() {
        String url = settings == null ? "" : settings.get(SERVER_KEY, "").strip();
        return url.isEmpty() ? Optional.empty() : Optional.of(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }

    /**
     * Sends a report the reader has seen and chosen to send. Off the JavaFX thread; the
     * future completes with the server's answer or fails with why it was not accepted.
     */
    public CompletableFuture<String> send(CrashReport report) {
        Optional<String> server = server();
        if (server.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No report server is set."));
        }
        String token = settings == null ? "" : settings.get(TOKEN_KEY, "");
        return send(report, server.get(), token);
    }

    /** Sends to a given server; for the dialog, and for testing a server before saving its address. */
    public static CompletableFuture<String> send(CrashReport report, String server, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server + "/api/reports"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(report.json(), StandardCharsets.UTF_8));
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token.strip());
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new IllegalStateException("The server answered " + response.statusCode()
                                + (response.body().isBlank() ? "" : ": " + response.body().strip()));
                    }
                    return response.body();
                });
    }

    // -------------------------------------------------------- previous session

    /**
     * Writes down that smIDE is running, and where from.
     *
     * <p>The working directory is kept because that is where the Java runtime writes its
     * crash log, {@code hs_err_pid<pid>.log} - into whatever folder smIDE was started from,
     * which is not something the next start could otherwise know.
     */
    private void markRunning() {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, ProcessHandle.current().pid() + "\n" + System.getProperty("user.dir") + "\n");
        } catch (IOException | RuntimeException e) {
            // Without it, a runtime crash goes unnoticed at the next start; everything else still works.
        }
    }

    /**
     * The report of a session that ended without closing, if there is one to make.
     *
     * <p>Only when the Java runtime left its crash log. A marker on its own means the last
     * session did not close properly, which is also what a forced quit, a killed process
     * or a laptop running out of battery look like - none of them smIDE's failure, and a
     * dialog about each would teach people to dismiss the dialog.
     */
    Optional<CrashReport> previousSession() {
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(marker);
            long pid = lines.isEmpty() ? -1 : Long.parseLong(lines.get(0).strip());
            String cwd = lines.size() > 1 ? lines.get(1).strip() : "";
            if (pid <= 0 || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                // Still running: a second window, not a crash.
                return Optional.empty();
            }
            Optional<Path> log = crashLog(pid, cwd);
            if (log.isEmpty()) {
                return Optional.empty();
            }
            String excerpt = excerpt(log.get());
            CrashReport report = CrashReport.withoutThrowable(CrashReport.PREVIOUS_SESSION,
                    "The Java runtime stopped smIDE the last time it ran, and wrote down why in " + log.get(),
                    version, excerpt);
            save(report);
            return Optional.of(report);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The runtime's crash log for that process, wherever the runtime will have put it. */
    private static Optional<Path> crashLog(long pid, String cwd) {
        String name = "hs_err_pid" + pid + ".log";
        List<Path> places = new ArrayList<>();
        if (!cwd.isEmpty()) {
            places.add(Path.of(cwd));
        }
        places.add(Path.of(System.getProperty("user.home")));
        places.add(Path.of(System.getProperty("java.io.tmpdir")));
        for (Path place : places) {
            Path candidate = place.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The part of a runtime crash log that says what happened: its header, down to where
     * the long lists of loaded libraries and memory maps begin.
     */
    private static String excerpt(Path log) throws IOException {
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        int taken = 0;
        for (String line : lines) {
            if (taken >= EXTRA_LINES || line.startsWith("---------------  P R O C E S S")) {
                break;
            }
            out.append(line).append('\n');
            taken++;
        }
        return out.toString();
    }
}
