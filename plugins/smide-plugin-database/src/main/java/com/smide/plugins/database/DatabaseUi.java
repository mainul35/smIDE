package com.smide.plugins.database;

import com.smide.api.Ide;
import com.smide.api.ui.StatusBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Keeps database work off the JavaFX thread, and asks for the password when one is
 * needed.
 *
 * <p>A query can take as long as it takes; a connection to a host that is not listening
 * takes until it times out. Neither belongs on the thread that draws the window.
 */
public final class DatabaseUi {

    private final Ide ide;
    private final Databases databases;

    public DatabaseUi(Ide ide, Databases databases) {
        this.ide = ide;
        this.databases = databases;
    }

    public Ide ide() {
        return ide;
    }

    public Databases databases() {
        return databases;
    }

    /** Runs something that reads from a database, then hands the answer back on the UI thread. */
    public <T> void read(Callable<T> work, Consumer<T> onDone) {
        ide.window().runInBackground(() -> {
            try {
                T value = work.call();
                ide.window().runLater(() -> onDone.accept(value));
            } catch (Exception e) {
                ide.window().runLater(() -> ide.notifications().error("Database", message(e)));
            }
        });
    }

    /**
     * The same, with progress on the status bar - for the things a user waits for, like
     * opening a connection.
     */
    public <T> void withProgress(String title, Callable<T> work, Consumer<T> onDone) {
        StatusBar.Progress progress = ide.statusBar().progress(title, false);
        ide.window().runInBackground(() -> {
            try {
                T value = work.call();
                ide.window().runLater(() -> {
                    progress.done();
                    onDone.accept(value);
                });
            } catch (Exception e) {
                ide.window().runLater(() -> {
                    progress.done();
                    ide.notifications().error(title + " failed", message(e));
                });
            }
        });
    }

    /**
     * Makes sure a password is known before connecting, asking for it once per run.
     *
     * <p>Nothing is written down: the answer is held in memory and forgotten when the
     * IDE closes, so a stored password can never be read out of a settings file.
     */
    public void withPassword(DataSource source, Runnable then) {
        if (databases.hasPassword(source)) {
            then.run();
            return;
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(ide.window().stage());
        dialog.setTitle("Password");
        dialog.setHeaderText(null);
        PasswordField field = new PasswordField();
        field.setPromptText("Password for " + source.user());
        Label explain = new Label("Password for " + source.describe()
                + ".\nKept in memory for this session only; it is never written to disk.");
        explain.getStyleClass().add("settings-note");
        explain.setWrapText(true);
        VBox box = new VBox(8, explain, field);
        box.setPadding(new javafx.geometry.Insets(12));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? field.getText() : null);
        ide.theme().style(dialog.getDialogPane());
        dialog.getDialogPane().getStylesheets().add(ide.theme().stylesheet());
        javafx.application.Platform.runLater(field::requestFocus);
        Optional<String> answer = dialog.showAndWait();
        if (answer.isPresent()) {
            databases.setPassword(source, answer.get());
            then.run();
        }
    }

    /** The part of a JDBC failure worth showing; the stack trace is not it. */
    static String message(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        // The driver puts its whole story on one line; the first sentence is the useful part.
        int stop = message.indexOf(". ");
        return stop > 20 ? message.substring(0, stop + 1) : message;
    }
}
