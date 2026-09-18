package com.smide.crash;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
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
 * there, where to report it, and what to do next.
 *
 * <p>Two rows of buttons for two separate questions. The first is where the report goes:
 * the report server, a GitHub issue, the clipboard, or nowhere - it is saved either way. The
 * second is what happens to smIDE, and the answer is never that it stops on its own: a
 * running IDE carries on, or is restarted when the reader asks; one that could not start is
 * tried again, or tried in safe mode, or closed - by the reader, not by the failure.
 *
 * <p>The details are the report exactly as it would be sent, and they change as the reader
 * types a note, so there is never a difference between what they were shown and what went.
 */
final class CrashDialog {

    private final CrashReporter reporter;
    private final Stage stage = new Stage();
    private final TextArea note = new TextArea();
    private final TextArea details = new TextArea();
    private final Label status = new Label();
    private final Hyperlink link = new Hyperlink();
    private final Button send = new Button("Send to server");
    private final Button issue = new Button("GitHub issue");
    private CrashReport report;
    private CrashReporter.Choice choice = CrashReporter.Choice.CLOSED;

    CrashDialog(CrashReporter reporter, CrashReport report, Window owner, Consumer<Window> styler) {
        this.reporter = reporter;
        this.report = report;
        boolean startup = CrashReport.STARTUP.equals(report.kind());

        Label title = new Label(title(report));
        title.getStyleClass().add("crash-title");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label headline = new Label(report.headline());
        Label where = new Label("In " + report.where() + ".");
        Label reassurance = new Label(reassurance(report, reporter));
        Label saved = new Label("Saved as " + CrashReport.clean(reporter.fileOf(report).toString()));
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

        Label privacy = new Label("Nothing is sent unless you press Send or GitHub issue. Your home folder is"
                + " written as ~ and passwords in addresses are removed.");
        privacy.setStyle("-fx-opacity: 0.75;");

        // ------------------------------------------------------ where it goes
        send.setDisable(reporter.server().isEmpty());
        if (reporter.server().isPresent()) {
            send.setTooltip(new Tooltip("Send to " + reporter.server().get()));
        }
        send.setOnAction(e -> send());
        issue.setTooltip(new Tooltip(reporter.github().describe()));
        issue.setOnAction(e -> fileIssue());
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(this.report.text());
            Clipboard.getSystemClipboard().setContent(content);
            say("Copied.", null);
        });
        Button reveal = new Button("Show file");
        reveal.setOnAction(e -> reveal(reporter.folder()));
        if (reporter.server().isEmpty()) {
            // Said in words: a disabled button shows no tooltip, so it could not say why.
            say("No report server is set - Settings > Tools > Crash Reports.", null);
        }
        link.setVisible(false);
        link.setManaged(false);
        link.setOnAction(e -> reporter.browse(link.getText()));
        Region reportGap = new Region();
        HBox.setHgrow(reportGap, Priority.ALWAYS);
        status.setWrapText(true);
        VBox said = new VBox(2, status, link);
        HBox reportRow = new HBox(8, said, reportGap, copy, reveal, issue, send);
        reportRow.setAlignment(Pos.CENTER_RIGHT);

        // ------------------------------------------------------ what happens next
        HBox nextRow = new HBox(8);
        nextRow.setAlignment(Pos.CENTER_RIGHT);
        Region nextGap = new Region();
        HBox.setHgrow(nextGap, Priority.ALWAYS);
        if (startup) {
            Button quit = new Button("Quit");
            quit.setOnAction(e -> choose(CrashReporter.Choice.QUIT));
            nextRow.getChildren().addAll(quit, nextGap);
            if (reporter.canRetryStart()) {
                Button safe = new Button("Start in safe mode");
                safe.setTooltip(new Tooltip("Without plugins and without reopening the last session's files"));
                safe.setOnAction(e -> choose(CrashReporter.Choice.RESTART_SAFE));
                Button retry = new Button("Try again");
                retry.setDefaultButton(true);
                retry.setOnAction(e -> choose(CrashReporter.Choice.RESTART));
                nextRow.getChildren().addAll(safe, retry);
            }
            // Closing the window is Quit only when there is nothing else it could mean.
            stage.setOnCloseRequest(e -> {
                if (choice == CrashReporter.Choice.CLOSED) {
                    choice = reporter.canRetryStart() ? CrashReporter.Choice.RESTART : CrashReporter.Choice.QUIT;
                }
            });
        } else {
            nextRow.getChildren().add(nextGap);
            if (reporter.canRestart()) {
                Button restart = new Button("Restart smIDE");
                restart.setTooltip(new Tooltip("Asks to save changed files, then starts smIDE again with the same"
                        + " files open. Worth it when the window is not behaving since the error."));
                restart.setOnAction(e -> choose(CrashReporter.Choice.RESTART));
                nextRow.getChildren().add(restart);
            }
            Button carryOn = new Button("Carry on");
            carryOn.setCancelButton(true);
            carryOn.setDefaultButton(true);
            carryOn.setOnAction(e -> choose(CrashReporter.Choice.CLOSED));
            nextRow.getChildren().add(carryOn);
        }

        /* A label that wraps is measured at one line by a VBox that has not been laid out yet,
           and then cut short with an ellipsis. Asking for its full height is what makes it
           wrap instead. */
        for (Label label : List.of(title, headline, where, reassurance, saved, privacy)) {
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.setMaxWidth(Double.MAX_VALUE);
        }

        VBox root = new VBox(10, title, headline, where, reassurance, saved, askNote, note, detailsPane,
                privacy, reportRow, nextRow);
        root.setPadding(new Insets(18));
        root.setPrefWidth(660);
        root.getStyleClass().add("crash-dialog");

        stage.setTitle("smIDE - " + switch (report.kind()) {
            case CrashReport.PREVIOUS_SESSION -> "closed unexpectedly";
            case CrashReport.STARTUP -> "could not start";
            default -> "unexpected error";
        });
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

    /** Shows the dialog and waits; returns what the reader chose. */
    CrashReporter.Choice showAndWait() {
        stage.showAndWait();
        return choice;
    }

    Stage stage() {
        return stage;
    }

    private void choose(CrashReporter.Choice chosen) {
        choice = chosen;
        stage.close();
    }

    private static String title(CrashReport report) {
        return switch (report.kind()) {
            case CrashReport.PREVIOUS_SESSION -> "smIDE closed unexpectedly the last time it ran";
            case CrashReport.STARTUP -> "smIDE could not start";
            default -> "smIDE ran into a problem";
        };
    }

    private static String reassurance(CrashReport report, CrashReporter reporter) {
        return switch (report.kind()) {
            case CrashReport.PREVIOUS_SESSION -> "The Java runtime itself stopped, which no code inside it can"
                    + " catch while it happens. smIDE was started again, and the files that were open are back.";
            case CrashReport.STARTUP -> reporter.canRetryStart()
                    ? "The window could not be built. Try again, or start in safe mode - without plugins"
                            + " and without the last session's files - to get back in and look."
                    : "The window could not be built, and this smIDE is not running under its supervisor,"
                            + " so it cannot start itself again. Start it again from its launcher.";
            default -> "smIDE is still running, and your files and open tabs are still there. What you were"
                    + " doing may not have finished - it is worth checking before carrying on.";
        };
    }

    private void send() {
        send.setDisable(true);
        say("Sending...", null);
        reporter.send(report).whenComplete((answer, failure) -> Platform.runLater(() -> {
            if (failure == null) {
                say("Sent to the report server. Thank you.", null);
                send.setText("Sent");
            } else {
                say("Not sent: " + messageOf(failure), null);
                send.setDisable(false);
            }
        }));
    }

    private void fileIssue() {
        issue.setDisable(true);
        say("Taking it to GitHub...", null);
        reporter.github().file(report).whenComplete((result, failure) -> Platform.runLater(() -> {
            issue.setDisable(false);
            if (failure != null) {
                say("No issue made: " + messageOf(failure), null);
                return;
            }
            switch (result.outcome()) {
                case CREATED -> {
                    say("Filed as issue #" + result.number() + ".", result.url());
                    issue.setText("Filed");
                    issue.setDisable(true);
                }
                case COMMENTED -> {
                    say("Already known as issue #" + result.number() + "; added there as a comment.", result.url());
                    issue.setText("Added");
                    issue.setDisable(true);
                }
                case BROWSER -> {
                    reporter.browse(result.url());
                    say("Opened a new issue in your browser with the report written in. Check it and submit it"
                            + " there. With a GitHub token in Settings > Tools > Crash Reports this is done here.", null);
                }
            }
        }));
    }

    private void say(String text, String url) {
        status.setText(text);
        boolean show = url != null;
        link.setText(show ? url : "");
        link.setVisible(show);
        link.setManaged(show);
        stage.sizeToScene();
    }

    private static String messageOf(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
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
