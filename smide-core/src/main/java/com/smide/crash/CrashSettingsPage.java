package com.smide.crash;

import com.smide.api.settings.SettingsPage;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Settings > Tools > Crash Reports: where a report goes when the reader chooses to send one.
 *
 * <p>There is no default server. A report carries stack traces and the names of files, and
 * where that goes is a decision for whoever runs smIDE, not one made for them by an address
 * baked into the build. The server that reads these is {@code smide-crash-server}, in this
 * repository.
 */
public final class CrashSettingsPage implements SettingsPage {

    private final CrashReporter reporter;

    public CrashSettingsPage(CrashReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public String path() {
        return "Tools/Crash Reports";
    }

    @Override
    public String keywords() {
        return "crash report error exception bug dashboard server send";
    }

    @Override
    public Node create(SettingsEditor editor) {
        TextField server = new TextField(editor.staged().get(CrashReporter.SERVER_KEY, ""));
        server.setPromptText("http://reports.example.com:8787");
        HBox.setHgrow(server, Priority.ALWAYS);
        server.textProperty().addListener((o, a, now) -> {
            if (now == null || now.isBlank()) {
                editor.staged().remove(CrashReporter.SERVER_KEY);
            } else {
                editor.staged().set(CrashReporter.SERVER_KEY, now.strip());
            }
        });
        HBox serverRow = new HBox(8, new Label("Server"), server);
        serverRow.setAlignment(Pos.CENTER_LEFT);

        PasswordField token = new PasswordField();
        token.setText(editor.staged().get(CrashReporter.TOKEN_KEY, ""));
        token.setPromptText("The token the server was started with");
        HBox.setHgrow(token, Priority.ALWAYS);
        token.textProperty().addListener((o, a, now) -> {
            if (now == null || now.isBlank()) {
                editor.staged().remove(CrashReporter.TOKEN_KEY);
            } else {
                editor.staged().set(CrashReporter.TOKEN_KEY, now.strip());
            }
        });
        HBox tokenRow = new HBox(8, new Label("Token"), token);
        tokenRow.setAlignment(Pos.CENTER_LEFT);

        Label result = new Label();
        result.setWrapText(true);
        Button test = new Button("Send a test report");
        test.setOnAction(e -> {
            String url = server.getText().strip();
            if (url.isEmpty()) {
                result.setText("Set the server's address first.");
                return;
            }
            result.setText("Sending...");
            test.setDisable(true);
            CrashReport probe = CrashReport.withoutThrowable("test",
                    "A test report from Settings, to check the server can be reached.", "settings", "");
            String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            CrashReporter.send(probe, base, token.getText()).whenComplete((answer, failure) -> Platform.runLater(() -> {
                test.setDisable(false);
                if (failure == null) {
                    result.setText("The server took it. It will be in the dashboard as a test report.");
                } else {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    result.setText("Not sent: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                }
            }));
        });
        HBox testRow = new HBox(8, test, result);
        testRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox dialog = new CheckBox("Show a dialog when something fails");
        dialog.setSelected(editor.staged().getBoolean(CrashReporter.DIALOG_KEY, true));
        dialog.selectedProperty().addListener((o, a, now) -> editor.staged().setBoolean(CrashReporter.DIALOG_KEY, now));

        Label section = new Label("REPORT SERVER");
        section.getStyleClass().add("settings-section");
        Label serverNote = new Label("Where a report goes when you press Send in the crash dialog. Nothing is"
                + " sent without that. Run smide-crash-server from this repository to have one, and open its"
                + " address in a browser for the dashboard.");
        serverNote.getStyleClass().add("settings-note");
        serverNote.setWrapText(true);
        Label local = new Label("REPORTS ON THIS MACHINE");
        local.getStyleClass().add("settings-section");
        Label localNote = new Label("Every failure is saved in " + CrashReport.clean(reporter.folder().toString())
                + " whether or not it is sent, and whether or not the dialog is shown.");
        localNote.getStyleClass().add("settings-note");
        localNote.setWrapText(true);

        return new VBox(8, section, serverRow, tokenRow, testRow, serverNote, local, dialog, localNote);
    }
}
