package com.smide.core;

import com.smide.api.ui.Theme;
import com.smide.api.ui.WindowService;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextInputDialog;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class WindowServiceImpl implements WindowService {

    private final Stage stage;
    private final HostServices hostServices;
    private final Theme theme;
    private final ExecutorService pool;

    public WindowServiceImpl(Stage stage, HostServices hostServices, Theme theme) {
        this.stage = stage;
        this.hostServices = hostServices;
        this.theme = theme;
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "smide-background-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public void runLater(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    @Override
    public void runInBackground(Runnable runnable) {
        pool.execute(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                System.err.println("smIDE: background task failed: " + t);
                t.printStackTrace();
            }
        });
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    @Override
    public boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.initOwner(stage);
        theme.style(alert.getDialogPane().getScene().getWindow());
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    @Override
    public void alert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.initOwner(stage);
        theme.style(alert.getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }

    @Override
    public Optional<String> prompt(String title, String label, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial == null ? "" : initial);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(label);
        dialog.initOwner(stage);
        theme.style(dialog.getDialogPane().getScene().getWindow());
        return dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }

    @Override
    public Optional<Path> chooseDirectory(String title, Path initial) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        if (initial != null && Files.isDirectory(initial)) {
            chooser.setInitialDirectory(initial.toFile());
        }
        File chosen = chooser.showDialog(stage);
        return Optional.ofNullable(chosen).map(File::toPath);
    }

    @Override
    public Optional<Path> chooseFile(String title, Path initial) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        if (initial != null && Files.isDirectory(initial)) {
            chooser.setInitialDirectory(initial.toFile());
        }
        File chosen = chooser.showOpenDialog(stage);
        return Optional.ofNullable(chosen).map(File::toPath);
    }

    @Override
    public Optional<Path> chooseSaveFile(String title, Path initial, String suggestedName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        if (initial != null && Files.isDirectory(initial)) {
            chooser.setInitialDirectory(initial.toFile());
        }
        if (suggestedName != null) {
            chooser.setInitialFileName(suggestedName);
        }
        File chosen = chooser.showSaveDialog(stage);
        return Optional.ofNullable(chosen).map(File::toPath);
    }

    @Override
    public void browse(String url) {
        if (hostServices != null) {
            hostServices.showDocument(url);
        }
    }

    @Override
    public void revealInFileManager(Path path) {
        runInBackground(() -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) && Files.isRegularFile(path)) {
                        desktop.browseFileDirectory(path.toFile());
                        return;
                    }
                    Path dir = Files.isDirectory(path) ? path : path.getParent();
                    if (dir != null && desktop.isSupported(Desktop.Action.OPEN)) {
                        desktop.open(dir.toFile());
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("smIDE: cannot reveal " + path + ": " + e);
            }
        });
    }
}
