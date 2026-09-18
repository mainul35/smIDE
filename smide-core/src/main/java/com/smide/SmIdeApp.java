package com.smide;

import com.smide.core.IdeImpl;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SmIdeApp extends Application {

    public static final String VERSION = "0.1.0";

    private IdeImpl ide;

    @Override
    public void start(Stage stage) {
        /* Before anything else can fail: from here on, an exception nothing handles - on this
           thread or any other - is saved and offered to the reader instead of being printed
           to a terminal a desktop launch does not have. */
        com.smide.crash.CrashReporter.install(homeDir(), VERSION);
        try {
            startIde(stage);
        } catch (Throwable t) {
            failedToStart(t);
        }
    }

    private static Path homeDir() {
        return Path.of(System.getProperty("smide.userHome", System.getProperty("user.home")), ".smide");
    }

    /**
     * A start that threw: said out loud, and written down.
     *
     * <p>Without this the terminal gets "Exception in Application start method" and nothing
     * more. The toolkit, left with no window, ends the application, {@link #stop()} calls
     * {@code System.exit(0)}, and the process is gone - with a success code - before the trace
     * is printed. A broken build then looks like an IDE that closes without a word. Started
     * from a desktop entry there is no terminal to read either, so it goes to a file as well.
     */
    private static void failedToStart(Throwable t) {
        System.err.println("smIDE could not start.");
        t.printStackTrace();
        try {
            Path logs = Path.of(System.getProperty("smide.userHome", System.getProperty("user.home")),
                    ".smide", "logs");
            Files.createDirectories(logs);
            Path file = logs.resolve("startup-failure.log");
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {
                out.println("smIDE " + VERSION + " could not start, " + LocalDateTime.now());
                t.printStackTrace(out);
            }
            System.err.println("Written to " + file);
        } catch (Exception ignored) {
            // Nowhere to write it is no reason to say less than has been said already.
        }
        System.err.flush();
        /* And shown, because a launch from the desktop has no terminal to print to: the
           dialog is the only way somebody who clicked an icon learns anything at all. This
           is the toolkit's own thread, inside start(), so it may wait for the dialog. If the
           failure was in the classes the dialog is made of, it will not appear, and what was
           written above is what there is. */
        com.smide.crash.CrashReporter.Choice choice = null;
        try {
            com.smide.crash.CrashReporter reporter = com.smide.crash.CrashReporter.install(homeDir(), VERSION);
            reporter.attach(new com.smide.settings.JsonSettings(homeDir().resolve("settings.json")), null, null);
            com.smide.crash.CrashReport report = reporter.report(com.smide.crash.CrashReport.STARTUP, t,
                    Thread.currentThread().getName(), false);
            if (report != null) {
                choice = reporter.show(report);
            }
        } catch (Throwable dialogFailed) {
            System.err.println("smIDE: the crash dialog could not be shown either: " + dialogFailed);
        }
        /* Nothing is left running in this process that could be used - the window was never
           built - so what happens next happens in a new one, and the supervisor starts it.
           Which one is the reader's choice: tried again, tried in safe mode, or not. When the
           dialog could not even be shown, the exit code says only that this start failed,
           and the supervisor decides - again, then safe mode, then stop - by how often.
           Not System.exit: stop() must not run, and no shutdown hook may cut the output short. */
        int code = choice == null ? 1 : switch (choice) {
            case RESTART, CLOSED -> Supervisor.supervised() ? Supervisor.RESTART : 1;
            case RESTART_SAFE -> Supervisor.RESTART_SAFE;
            case QUIT -> 0;
        };
        Runtime.getRuntime().halt(code);
    }

    /** How this process ends when the window closes: 0, or a request to its supervisor to start it again. */
    private static volatile int exitCode = 0;

    /** Ends with this code when the window next closes: {@link Supervisor#RESTART} to be started again. */
    public static void exitWith(int code) {
        exitCode = code;
    }

    private void startIde(Stage stage) {
        // Before any window is built, so the first frame is drawn in the right font
        // rather than in whatever the platform had and then repainted.
        com.smide.ui.Fonts.load();
        List<Path> openOnStart = new ArrayList<>();
        Parameters params = getParameters();
        if (params != null) {
            for (String raw : params.getRaw()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                try {
                    openOnStart.add(Path.of(raw).toAbsolutePath().normalize());
                } catch (RuntimeException e) {
                    System.err.println("smIDE: ignoring argument " + raw);
                }
            }
        }
        ide = new IdeImpl(stage, getHostServices());
        ide.start(openOnStart);
    }

    @Override
    public void stop() {
        if (ide != null) {
            ide.shutdown();
        }
        /* And then actually go.
         *
         * Closing the window ends the JavaFX toolkit and nothing else: the JVM stays up
         * for as long as any non-daemon thread is alive, and those come from libraries -
         * a language server's reader, a pty, a JGit worker - not from code here. The
         * symptom is a window that has gone and a terminal that never gets its prompt
         * back, which reads as a hang because it is one.
         *
         * After shutdown, so everything that had to be written has been written. Nothing
         * of ours is expected to be running by this point; this is for what we do not
         * own. */
        Platform.exit();
        System.exit(exitCode);
    }

    /**
     * The first process started is not the IDE but its {@link Supervisor}, which starts the
     * IDE as a child and starts it again if it dies. The child, told so through its
     * environment, is the IDE.
     */
    public static void main(String[] args) {
        if (Supervisor.shouldSupervise()) {
            Supervisor.runSupervised(args);
        } else {
            launchIde(args);
        }
    }

    /** The IDE itself, in this process. */
    static void launchIde(String[] args) {
        launch(args);
    }
}
