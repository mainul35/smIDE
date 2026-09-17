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
        try {
            startIde(stage);
        } catch (Throwable t) {
            failedToStart(t);
        }
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
        // Not System.exit: stop() must not run, and no shutdown hook may cut the output short.
        Runtime.getRuntime().halt(1);
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
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
