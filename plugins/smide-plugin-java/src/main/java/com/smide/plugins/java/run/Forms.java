package com.smide.plugins.java.run;

import com.smide.api.Ide;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.nio.file.Path;
import java.util.List;

/** Small form builders that write straight into a configuration's values. */
public final class Forms {

    private Forms() {
    }

    public static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(4, 0, 4, 0));
        javafx.scene.layout.ColumnConstraints label = new javafx.scene.layout.ColumnConstraints();
        label.setMinWidth(130);
        javafx.scene.layout.ColumnConstraints field = new javafx.scene.layout.ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, field);
        return grid;
    }

    public static TextField text(GridPane grid, int row, String label, BaseRunConfiguration c, String key, String prompt) {
        TextField field = new TextField(c.get(key, ""));
        field.setPromptText(prompt);
        field.textProperty().addListener((o, a, b) -> c.set(key, b));
        grid.add(new Label(label), 0, row);
        grid.add(field, 1, row);
        return field;
    }

    public static ComboBox<String> combo(GridPane grid, int row, String label, BaseRunConfiguration c, String key,
                                         List<String> options) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setEditable(true);
        combo.getItems().setAll(options);
        combo.setValue(c.get(key, options.isEmpty() ? "" : options.get(0)));
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.valueProperty().addListener((o, a, b) -> c.set(key, b));
        combo.getEditor().textProperty().addListener((o, a, b) -> c.set(key, b));
        grid.add(new Label(label), 0, row);
        grid.add(combo, 1, row);
        return combo;
    }

    public static CheckBox check(GridPane grid, int row, String label, BaseRunConfiguration c, String key, boolean def) {
        CheckBox box = new CheckBox(label);
        box.setSelected(c.flag(key, def));
        box.selectedProperty().addListener((o, a, b) -> c.set(key, String.valueOf(b)));
        grid.add(box, 1, row);
        return box;
    }

    public static Node directory(GridPane grid, int row, String label, BaseRunConfiguration c, String key, Ide ide) {
        TextField field = new TextField(c.get(key, ""));
        field.setPromptText(c.workspace().root().toString());
        field.textProperty().addListener((o, a, b) -> c.set(key, b));
        javafx.scene.control.Button browse = new javafx.scene.control.Button("...");
        browse.setOnAction(e -> ide.window().chooseDirectory(label, c.workspace().root())
                .ifPresent(p -> field.setText(p.toString())));
        HBox box = new HBox(4, field, browse);
        HBox.setHgrow(field, Priority.ALWAYS);
        grid.add(new Label(label), 0, row);
        grid.add(box, 1, row);
        return box;
    }

    public static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-note");
        label.setWrapText(true);
        return label;
    }

    /** Splits a command-line string on spaces, honouring double quotes. */
    public static List<String> splitArgs(String text) {
        List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /**
     * Reads an environment from the form field: {@code KEY=value;OTHER=value}.
     *
     * <p>Semicolons separate, the first {@code =} splits, and nothing else is special,
     * so a value may contain {@code =} - a connection string usually does.
     */
    public static java.util.Map<String, String> environment(String text) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String pair : text.split(";")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                out.put(pair.substring(0, equals).strip(), pair.substring(equals + 1).strip());
            }
        }
        return out;
    }

    public static String relative(Path root, Path dir) {
        try {
            return root.equals(dir) ? "" : root.relativize(dir).toString();
        } catch (RuntimeException e) {
            return dir.toString();
        }
    }
}
