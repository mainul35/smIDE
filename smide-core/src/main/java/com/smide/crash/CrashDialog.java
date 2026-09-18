package com.smide.crash;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the reader sees when something failed: what happened, that their work is still
 * there, and the choice of sending it.
 *
 * <p>The details are the report exactly as it would be sent, and they change as the reader
 * types a note, so there is never a difference between what they were shown and what went.
 * Send is only offered when a report server has been set; without one the report is still
 * saved, can be copied, and can be opened where it lies.
 */
final class CrashDialog {

    private final CrashReporter reporter;
    private final Stage stage = new Stage();
    private final TextArea note = new TextArea();
    private final TextArea details = new TextArea();
    private final Label status = new Label();
    private final Button send = new Button("Send report");
    private CrashReport report;

    CrashDialog(CrashReporter reporter, CrashReport report, Window owner, Consumer<Window> styler) {
        this.reporter = reporter;
        this.report = report;

        Label title = new Label(title(report));
        title.getStyleClass().add("crash-title");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        title.setWrapText(true);

        Label headline = new Label(report.headline());
        headline.setWrapText(true);
        Label where = new Label("In " + report.where() + ".");
        where.setWrapText(true);
        Label reassurance = new Label(reassurance(report));
        reassurance.setWrapText(true);
        Label saved = new Label("Saved as " + CrashReport.clean(reporter.fileOf(report).toString()));
        saved.setWrapText(true);
        saved.setStyle("-fx-opacity: 0.75;");

        Label askNote = new Label("What were you doing when it happened? (optional)");
        note.setPromptText("For example: I had just pressed Run on a Go file");
        note.setPrefRowCount(3);
        note.setWrapText(true);
        note.textProperty().addListener((property, was, now) -> {
            this.report = report.withNote(now);
            details.setText(this.report.text());
        });

        details.setEditable(false);
        details.setWrapText(false);
        details.setPrefRowCount(14);
        details.setText(report.text());
        try {
            details.setStyle("-fx-font-family: \"" + com.smide.ui.Fonts.monospace() + "\";");
        } catch (RuntimeException | LinkageError e) {
            details.setStyle("-fx-font-family: monospace;");
        }
        TitledPane detailsPane = new TitledPane("Details - exactly what would be sent", details);
        detailsPane.setExpanded(false);
        // Not animated: the window is resized to fit, and mid-animation there is nothing to fit yet.
        detailsPane.setAnimated(false);
        detailsPane.expandedProperty().addListener((property, was, now) -> stage.sizeToScene());

        Label privacy = new Label("Nothing is sent unless you press Send. Your home folder is written as ~"
                + " and passwords in addresses are removed.");
        privacy.setWrapText(true);
        privacy.setStyle("-fx-opacity: 0.75;");

        send.setDefaultButton(reporter.server().isPresent());
        send.setDisable(reporter.server().isEmpty());
        if (reporter.server().isEmpty()) {
            // Said in words: a disabled button shows no tooltip, so it could not say why.
            status.setText("No report server is set - Settings > Tools > Crash Reports.");
        } else {
            send.setTooltip(new Tooltip("Send to " + reporter.server().get()));
        }
        send.setOnAction(e -> send());

        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(this.report.text());
            Clipboard.getSystemClipboard().setContent(content);
            status.setText("Copied.");
        });
        Button reveal = new Button("Show file");
        reveal.setOnAction(e -> reveal(reporter.folder()));
        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        status.setWrapText(true);
        HBox buttons = new HBox(8, status, gap, copy, reveal, send, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        /* A label that wraps is measured at one line by a VBox that has not been laid out yet,
           and then cut short with an ellipsis. Asking for its full height is what makes it
           wrap instead. */
        for (Label label : List.of(title, headline, where, reassurance, saved, privacy)) {
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.setMaxWidth(Double.MAX_VALUE);
        }

        VBox root = new VBox(10, title, headline, where, reassurance, saved, askNote, note, detailsPane,
                privacy, buttons);
        root.setPadding(new Insets(18));
        root.setPrefWidth(620);
        root.getStyleClass().add("crash-dialog");

        stage.setTitle("smIDE - " + (CrashReport.PREVIOUS_SESSION.equals(report.kind())
                ? "closed unexpectedly" : "unexpected error"));
        stage.setScene(new Scene(root));
        if (owner != null && owner.isShowing()) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }
        try {
            styler.accept(stage);
        } catch (RuntimeException e) {
            // JavaFX's own colours are fine for a dialog about a failure.
        }
    }

    void showAndWait() {
        stage.showAndWait();
    }

    Stage stage() {
        return stage;
    }

    private static String title(CrashReport report) {
        return switch (report.kind()) {
            case CrashReport.PREVIOUS_SESSION -> "smIDE closed unexpectedly the last time it ran";
            case CrashReport.STARTUP -> "smIDE could not start";
            default -> "smIDE ran into a problem";
        };
    }

    private static String reassurance(CrashReport report) {
        return switch (report.kind()) {
            case CrashReport.PREVIOUS_SESSION -> "The Java runtime itself stopped, which no code in smIDE"
                    + " can catch while it happens. Files you had saved are as you left them.";
            case CrashReport.STARTUP -> "The window could not be built. smIDE will close when you close this.";
            default -> "Your files and open tabs are still there. What you were doing may not have"
                    + " finished - it is worth checking before carrying on.";
        };
    }

    private void send() {
        send.setDisable(true);
        status.setText("Sending...");
        CrashReport sending = report;
        reporter.send(sending).whenComplete((answer, failure) -> Platform.runLater(() -> {
            if (failure == null) {
                status.setText("Sent. Thank you.");
                send.setText("Sent");
            } else {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                status.setText("Not sent: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                send.setDisable(false);
            }
        }));
    }

    /** Opens the folder the reports are in, off this thread: the desktop's file manager can be slow to answer. */
    private static void reveal(Path folder) {
        Thread opener = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(folder.toFile());
                }
            } catch (Exception e) {
                System.err.println("smIDE: cannot open " + folder + ": " + e);
            }
        }, "smide-reveal-crashes");
        opener.setDaemon(true);
        opener.start();
    }
}
