package com.smide;

import com.smide.core.IdeImpl;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SmIdeApp extends Application {

    public static final String VERSION = "0.1.0";

    private IdeImpl ide;

    @Override
    public void start(Stage stage) {
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
