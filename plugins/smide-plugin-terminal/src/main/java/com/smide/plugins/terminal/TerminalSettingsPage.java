package com.smide.plugins.terminal;

import com.smide.api.Ide;
import com.smide.api.settings.SettingsPage;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

/** Settings > Tools > Terminal: which shell to run and how big the text is. */
final class TerminalSettingsPage implements SettingsPage {

    private final Ide ide;

    TerminalSettingsPage(Ide ide) {
        this.ide = ide;
    }

    @Override
    public String path() {
        return "Tools/Terminal";
    }

    @Override
    public String keywords() {
        return "terminal shell console pwsh powershell bash font";
    }

    @Override
    public Node create(SettingsEditor editor) {
        TextField shell = new TextField(editor.staged().get(ShellResolver.SHELL_KEY, ""));
        shell.setPromptText("Default: " + ShellResolver.defaultShellDescription());
        shell.textProperty().addListener((o, a, b) -> {
            if (b == null || b.isBlank()) {
                editor.staged().remove(ShellResolver.SHELL_KEY);
            } else {
                editor.staged().set(ShellResolver.SHELL_KEY, b.strip());
            }
        });
        HBox.setHgrow(shell, Priority.ALWAYS);
        Button browse = new Button("Browse...");
        browse.setOnAction(e -> ide.window()
                .chooseFile("Choose a shell", Path.of(System.getProperty("user.home")))
                .ifPresent(path -> shell.setText(path.toString())));
        HBox shellRow = new HBox(8, new Label("Shell"), shell, browse);
        shellRow.setAlignment(Pos.CENTER_LEFT);

        Spinner<Integer> size = new Spinner<>(6, 40,
                editor.staged().getInt(ThemedSettingsProvider.FONT_SIZE_KEY, ThemedSettingsProvider.DEFAULT_FONT_SIZE));
        size.setEditable(true);
        size.valueProperty().addListener((o, a, b) -> editor.staged().setInt(ThemedSettingsProvider.FONT_SIZE_KEY, b));
        HBox fontRow = new HBox(8, new Label("Font size"), size);
        fontRow.setAlignment(Pos.CENTER_LEFT);

        Label section = new Label("SHELL");
        section.getStyleClass().add("settings-section");
        Label shellNote = new Label("An executable, optionally with arguments. Leave empty for the platform default. "
                + "Applies to terminals opened after Apply.");
        shellNote.getStyleClass().add("settings-note");
        Label fontSection = new Label("APPEARANCE");
        fontSection.getStyleClass().add("settings-section");
        Label fontNote = new Label("Colours follow the IDE theme; the font is Cascadia Mono or Consolas. "
                + "Size changes apply to open terminals on Apply.");
        fontNote.getStyleClass().add("settings-note");

        return new VBox(8, section, shellRow, shellNote, fontSection, fontRow, fontNote);
    }
}
