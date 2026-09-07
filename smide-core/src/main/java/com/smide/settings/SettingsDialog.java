package com.smide.settings;

import com.smide.api.action.Action;
import com.smide.api.settings.SettingsPage;
import com.smide.core.ExtensionRegistry;
import com.smide.core.IdeImpl;
import com.smide.core.PluginManager;
import com.smide.theme.ThemeManager;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Settings dialog: a tree of pages on the left, the page on the right, OK / Apply /
 * Cancel. Pages write to a staged copy; Apply copies it into the real settings.
 */
public final class SettingsDialog {

    private final IdeImpl ide;
    private final ExtensionRegistry registry;
    private final JsonSettings staged = new JsonSettings();
    private final List<Runnable> applyCallbacks = new ArrayList<>();
    private final Map<String, SettingsPage> pages = new LinkedHashMap<>();
    private final BorderPane content = new BorderPane();
    private final Map<String, Node> built = new LinkedHashMap<>();
    private Stage stage;

    public SettingsDialog(IdeImpl ide, ExtensionRegistry registry) {
        this.ide = ide;
        this.registry = registry;
        staged.putAll(ide.jsonSettings().snapshot());
        addBuiltinPages();
        for (SettingsPage page : registry.settingsPages()) {
            pages.put(page.path(), page);
        }
    }

    public void show(String initialPath) {
        stage = new Stage();
        stage.initOwner(ide.window().stage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Settings");

        TreeItem<String> root = new TreeItem<>("Settings");
        Map<String, TreeItem<String>> nodes = new LinkedHashMap<>();
        for (String path : pages.keySet()) {
            TreeItem<String> parent = root;
            StringBuilder prefix = new StringBuilder();
            for (String part : path.split("/")) {
                if (prefix.length() > 0) {
                    prefix.append('/');
                }
                prefix.append(part);
                TreeItem<String> item = nodes.get(prefix.toString());
                if (item == null) {
                    item = new TreeItem<>(part);
                    item.setExpanded(true);
                    nodes.put(prefix.toString(), item);
                    parent.getChildren().add(item);
                }
                parent = item;
            }
        }
        TreeView<String> tree = new TreeView<>(root);
        tree.setShowRoot(false);
        tree.getStyleClass().add("settings-tree");
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> {
            if (item != null) {
                showPage(pathOf(item));
            }
        });

        Button ok = new Button("OK");
        Button apply = new Button("Apply");
        Button cancel = new Button("Cancel");
        ok.setDefaultButton(true);
        cancel.setCancelButton(true);
        ok.setOnAction(e -> {
            apply();
            stage.close();
        });
        apply.setOnAction(e -> apply());
        cancel.setOnAction(e -> stage.close());
        ButtonBar buttons = new ButtonBar();
        buttons.getButtons().addAll(ok, cancel, apply);
        buttons.setPadding(new Insets(10));

        SplitPane split = new SplitPane(tree, content);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.26);
        BorderPane pane = new BorderPane(split);
        pane.setBottom(buttons);
        Scene scene = new Scene(pane, 900, 620);
        stage.setScene(scene);
        ide.theme().style(stage);

        String target = initialPath != null && nodes.containsKey(initialPath) ? initialPath
                : pages.keySet().iterator().next();
        TreeItem<String> initial = nodes.get(target);
        tree.getSelectionModel().select(initial);
        stage.show();
    }

    private static String pathOf(TreeItem<String> item) {
        List<String> parts = new ArrayList<>();
        TreeItem<String> current = item;
        while (current != null && current.getParent() != null) {
            parts.add(0, current.getValue());
            current = current.getParent();
        }
        return String.join("/", parts);
    }

    private void showPage(String path) {
        SettingsPage page = pages.get(path);
        if (page == null) {
            Label none = new Label(path);
            none.getStyleClass().add("settings-heading");
            content.setCenter(new VBox(none));
            return;
        }
        Node node = built.computeIfAbsent(path, p -> {
            Node built = page.create(new SettingsPage.SettingsEditor() {
                @Override
                public com.smide.api.settings.Settings staged() {
                    return staged;
                }

                @Override
                public void onApply(Runnable callback) {
                    applyCallbacks.add(callback);
                }
            });
            VBox box = new VBox(10);
            box.getStyleClass().add("settings-page");
            Label heading = new Label(path.substring(path.lastIndexOf('/') + 1));
            heading.getStyleClass().add("settings-heading");
            box.getChildren().addAll(heading, built);
            ScrollPane scroll = new ScrollPane(box);
            scroll.setFitToWidth(true);
            return scroll;
        });
        content.setCenter(node);
    }

    private void apply() {
        applyCallbacks.forEach(Runnable::run);
        ide.jsonSettings().putAll(staged.snapshot());
        // Keys removed in the staged copy are removed for real too.
        for (String key : ide.jsonSettings().snapshot().keySet()) {
            if (staged.get(key, null) == null) {
                ide.jsonSettings().remove(key);
            }
        }
        ide.theme().setDark(staged.getBoolean(ThemeManager.SETTING_KEY, false));
    }

    // ------------------------------------------------------------ built-in pages

    private void addBuiltinPages() {
        pages.put("Appearance", new SettingsPage() {
            @Override
            public String path() {
                return "Appearance";
            }

            @Override
            public Node create(SettingsEditor editor) {
                CheckBox dark = new CheckBox("Dark theme");
                dark.setSelected(editor.staged().getBoolean(ThemeManager.SETTING_KEY, false));
                dark.selectedProperty().addListener((o, a, b) -> editor.staged().setBoolean(ThemeManager.SETTING_KEY, b));
                CheckBox autoscroll = new CheckBox("Select the open file in the Project tool window");
                autoscroll.setSelected(editor.staged().getBoolean("explorer.autoscroll", true));
                autoscroll.selectedProperty().addListener((o, a, b) -> editor.staged().setBoolean("explorer.autoscroll", b));
                CheckBox restore = new CheckBox("Reopen workspaces and files from the last session");
                restore.setSelected(editor.staged().getBoolean("session.restore", true));
                restore.selectedProperty().addListener((o, a, b) -> editor.staged().setBoolean("session.restore", b));
                return new VBox(8, dark, autoscroll, restore);
            }
        });
        pages.put("Editor", new SettingsPage() {
            @Override
            public String path() {
                return "Editor";
            }

            @Override
            public Node create(SettingsEditor editor) {
                Spinner<Integer> size = new Spinner<>(8, 40, editor.staged().getInt("editor.fontSize", 13));
                size.setEditable(true);
                size.valueProperty().addListener((o, a, b) -> editor.staged().setInt("editor.fontSize", b));
                TextField font = new TextField(editor.staged().get("editor.fontFamily", com.smide.editor.CodeEditor.DEFAULT_FONT));
                font.textProperty().addListener((o, a, b) -> editor.staged().set("editor.fontFamily", b));
                CheckBox wrap = new CheckBox("Soft-wrap long lines");
                wrap.setSelected(editor.staged().getBoolean("editor.wrap", false));
                wrap.selectedProperty().addListener((o, a, b) -> editor.staged().setBoolean("editor.wrap", b));
                CheckBox lineHighlight = new CheckBox("Highlight the caret line");
                lineHighlight.setSelected(editor.staged().getBoolean("editor.highlightLine", true));
                lineHighlight.selectedProperty().addListener((o, a, b) -> editor.staged().setBoolean("editor.highlightLine", b));
                Label note = new Label("Applies to every open editor as soon as you press Apply.");
                note.getStyleClass().add("settings-note");
                HBox fontRow = new HBox(8, new Label("Font"), font, new Label("Size"), size);
                fontRow.setStyle("-fx-alignment: center-left;");
                return new VBox(8, fontRow, wrap, lineHighlight, note);
            }
        });
        pages.put("Keymap", new SettingsPage() {
            @Override
            public String path() {
                return "Keymap";
            }

            @Override
            public Node create(SettingsEditor editor) {
                record Row(String id, String text, javafx.beans.property.SimpleStringProperty shortcut) {
                }
                TableView<Row> table = new TableView<>();
                table.setEditable(true);
                TableColumn<Row, String> action = new TableColumn<>("Action");
                action.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().text()));
                action.setPrefWidth(300);
                TableColumn<Row, String> where = new TableColumn<>("Menu");
                where.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                        ide.actions().byId(c.getValue().id()).map(Action::menuPath).orElse("")));
                where.setPrefWidth(160);
                TableColumn<Row, String> shortcut = new TableColumn<>("Shortcut (double-click to edit, e.g. shortcut+shift+N)");
                shortcut.setCellValueFactory(c -> c.getValue().shortcut());
                shortcut.setCellFactory(TextFieldTableCell.forTableColumn());
                shortcut.setOnEditCommit(e -> {
                    String value = e.getNewValue() == null ? "" : e.getNewValue().strip();
                    if (!value.isEmpty() && com.smide.actions.ActionManager.parse(value) == null) {
                        ide.statusBar().message("Not a valid shortcut: " + value);
                        return;
                    }
                    e.getRowValue().shortcut().set(value);
                    editor.staged().set("keymap." + e.getRowValue().id(), value);
                });
                shortcut.setPrefWidth(300);
                table.getColumns().addAll(action, where, shortcut);
                for (Action a : ide.actions().all()) {
                    String current = editor.staged().get("keymap." + a.id(), a.shortcut() == null ? "" : a.shortcut());
                    table.getItems().add(new Row(a.id(), a.text(), new javafx.beans.property.SimpleStringProperty(current)));
                }
                table.getItems().sort((x, y) -> x.text().compareToIgnoreCase(y.text()));
                table.setPrefHeight(480);
                Label note = new Label("Modifiers: shortcut (Ctrl, or Cmd on macOS), ctrl, alt, shift, meta. Keys as JavaFX names: F10, SLASH, UP.");
                note.getStyleClass().add("settings-note");
                return new VBox(8, table, note);
            }
        });
        pages.put("Plugins", new SettingsPage() {
            @Override
            public String path() {
                return "Plugins";
            }

            @Override
            public Node create(SettingsEditor editor) {
                VBox box = new VBox(6);
                List<String> disabled = new ArrayList<>(editor.staged().getList("plugins.disabled"));
                for (PluginManager.LoadedPlugin p : ide.pluginManager().loaded()) {
                    CheckBox cb = new CheckBox(p.descriptor().name() + "  " + p.descriptor().version()
                            + (p.isStarted() ? "" : "   (" + p.error() + ")"));
                    cb.setSelected(!disabled.contains(p.descriptor().id()));
                    cb.selectedProperty().addListener((o, a, b) -> {
                        if (b) {
                            disabled.remove(p.descriptor().id());
                        } else if (!disabled.contains(p.descriptor().id())) {
                            disabled.add(p.descriptor().id());
                        }
                        editor.staged().setList("plugins.disabled", disabled);
                    });
                    Label desc = new Label(p.descriptor().id()
                            + (p.descriptor().description().isEmpty() ? "" : "  -  " + p.descriptor().description()));
                    desc.getStyleClass().add("settings-note");
                    box.getChildren().addAll(cb, desc);
                }
                Label note = new Label("Enabling or disabling a plugin takes effect after a restart.");
                note.getStyleClass().add("settings-note");
                box.getChildren().add(note);
                return box;
            }
        });
    }
}
