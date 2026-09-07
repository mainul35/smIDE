package com.smide;

import com.smide.core.IdeImpl;
import javafx.application.Application;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SmIdeApp extends Application {

    public static final String VERSION = "0.1.0";

    private IdeImpl ide;

    @Override
    public void start(Stage stage) {
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
    }

    public static void main(String[] args) {
        launch(args);
    }
}
