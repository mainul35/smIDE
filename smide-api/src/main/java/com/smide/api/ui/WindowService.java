package com.smide.api.ui;

import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Optional;

public interface WindowService {

    Stage stage();

    /** Runs on the JavaFX thread, now if already there. */
    void runLater(Runnable runnable);

    /** Runs on a shared background pool. */
    void runInBackground(Runnable runnable);

    boolean confirm(String title, String message);

    void alert(String title, String message);

    Optional<String> prompt(String title, String label, String initial);

    Optional<Path> chooseDirectory(String title, Path initial);

    Optional<Path> chooseFile(String title, Path initial);

    Optional<Path> chooseSaveFile(String title, Path initial, String suggestedName);

    /** Opens a URL in the system browser. */
    void browse(String url);

    /** Shows the file in the OS file manager. */
    void revealInFileManager(Path path);
}
