package com.smide.api.lang;

import com.smide.api.Ide;
import com.smide.api.settings.SettingsPage;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/**
 * A settings page for the toolchains a language needs: where each one is, whether it was
 * found, and where to get it - so a language plugin gets one without writing a form.
 */
public final class ToolchainSettingsPage implements SettingsPage {

    private final Ide ide;
    private final String path;
    private final List<Toolchain> toolchains;

    /** @param path where the page sits in the settings tree: {@code Languages/Python} */
    public ToolchainSettingsPage(Ide ide, String path, Toolchain... toolchains) {
        this.ide = ide;
        this.path = path;
        this.toolchains = List.of(toolchains);
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String keywords() {
        StringBuilder words = new StringBuilder("toolchain runtime interpreter compiler sdk install location");
        toolchains.forEach(t -> words.append(' ').append(t.displayName()).append(' ').append(t.id()));
        return words.toString();
    }

    @Override
    public Node create(SettingsEditor editor) {
        VBox page = new VBox(16);
        for (Toolchain toolchain : toolchains) {
            page.getChildren().add(section(editor, toolchain));
        }
        return page;
    }

    private Node section(SettingsEditor editor, Toolchain toolchain) {
        Label heading = new Label(toolchain.displayName());
        heading.setStyle("-fx-font-weight: bold;");
        String found = toolchain.locate(ide).map(p -> "Found: " + p).orElse("Not found on this machine.");
        Label status = new Label(found);
        status.getStyleClass().add("settings-note");
        VBox section = new VBox(6, heading, status);

        if (toolchain.homeSetting() != null) {
            TextField home = new TextField(editor.staged().get(toolchain.homeSetting(), ""));
            home.setPromptText("empty: found automatically");
            Label verdict = new Label();
            verdict.getStyleClass().add("error-text");
            verdict.setWrapText(true);
            verdict.setVisible(false);
            verdict.setManaged(false);
            home.textProperty().addListener((o, was, now) -> {
                editor.staged().set(toolchain.homeSetting(), now);
                boolean bad = !now.isBlank() && !toolchain.accepts(Path.of(now.strip()));
                verdict.setText(bad ? now.strip() + " does not look like a " + toolchain.displayName()
                        + " installation." : "");
                verdict.setVisible(bad);
                verdict.setManaged(bad);
            });
            Button browse = new Button("...");
            browse.setOnAction(e -> ide.window()
                    .chooseDirectory(toolchain.displayName() + " location", Path.of(System.getProperty("user.home", ".")))
                    .ifPresent(p -> home.setText(p.toString())));
            HBox row = new HBox(4, new Label("Installation"), home, browse);
            row.setStyle("-fx-alignment: center-left;");
            HBox.setHgrow(home, Priority.ALWAYS);
            section.getChildren().addAll(row, verdict);
        }
        if (toolchain.downloadUrl() != null) {
            Button download = new Button("Download " + toolchain.displayName() + "...");
            download.setOnAction(e -> ide.window().browse(toolchain.downloadUrl()));
            section.getChildren().add(download);
        }
        Label purpose = new Label(toolchain.purpose());
        purpose.getStyleClass().add("settings-note");
        purpose.setWrapText(true);
        section.getChildren().add(purpose);
        return section;
    }
}
