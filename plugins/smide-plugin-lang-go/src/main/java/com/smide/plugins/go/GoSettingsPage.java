package com.smide.plugins.go;

import com.smide.api.Ide;
import com.smide.api.settings.SettingsPage;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

/** Settings → Languages → Go: where the Go toolchain is. */
public final class GoSettingsPage implements SettingsPage {

    private final Ide ide;
    private final GoToolchain toolchain;

    public GoSettingsPage(Ide ide, GoToolchain toolchain) {
        this.ide = ide;
        this.toolchain = toolchain;
    }

    @Override
    public String path() {
        return "Languages/Go";
    }

    @Override
    public String keywords() {
        return "go golang toolchain goroot gopls";
    }

    @Override
    public Node create(SettingsEditor editor) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(120);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, field);

        TextField home = new TextField(editor.staged().get(GoToolchain.HOME_SETTING, ""));
        home.setPromptText(toolchain.locate(ide).map(p -> p + "  (found)")
                .orElse("not found - install Go, or choose the folder it is in"));
        Label verdict = new Label();
        verdict.getStyleClass().add("error-text");
        verdict.setWrapText(true);
        home.textProperty().addListener((o, was, now) -> {
            editor.staged().set(GoToolchain.HOME_SETTING, now);
            boolean bad = !now.isBlank() && !toolchain.accepts(Path.of(now.strip()));
            verdict.setText(bad ? now.strip() + " has no bin/go - choose the folder Go is installed in." : "");
            verdict.setVisible(bad);
            verdict.setManaged(bad);
        });
        verdict.setVisible(false);
        verdict.setManaged(false);
        Button browse = new Button("...");
        browse.setOnAction(e -> ide.window()
                .chooseDirectory("Go installation", Path.of(System.getProperty("user.home", ".")))
                .ifPresent(p -> home.setText(p.toString())));
        HBox row = new HBox(4, home, browse);
        HBox.setHgrow(home, Priority.ALWAYS);
        Button download = new Button("Download Go...");
        download.setOnAction(e -> ide.window().browse(GoToolchain.DOWNLOAD));

        grid.addRow(0, new Label("Go installation"), row);
        grid.add(verdict, 1, 1);
        grid.add(download, 1, 2);

        Label note = new Label("The folder Go is installed in - the one holding bin/go. Empty means the go command"
                + " on the PATH, GOROOT, or where the installers put it. Running, testing and gopls all use it.");
        note.getStyleClass().add("settings-note");
        note.setWrapText(true);
        return new VBox(10, grid, note);
    }
}
