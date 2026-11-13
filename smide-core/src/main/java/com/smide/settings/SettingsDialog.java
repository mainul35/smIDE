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
import javafx.scene.control.ChoiceBox;
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
        com.smide.api.ui.Windows.belongsTo(stage, ide.window().stage());
        stage.setTitle("Settings");

        Map<String, TreeItem<String>> nodes = new LinkedHashMap<>();
        TreeView<String> tree = new TreeView<>(fill(new TreeItem<>("Settings"), nodes, ""));
        tree.setShowRoot(false);
        tree.getStyleClass().add("settings-tree");
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> {
            if (item != null) {
                showPage(pathOf(item));
            }
        });

        /* Finding a page by what is on it rather than by what it is called. Nobody looks
           for "Appearance" - they look for "dark" - so the tree is filtered on each page's
           own keywords as well as its name, and a page that has declared none can still be
           found by its path. Typing narrows the tree and lands on the first page left, so
           one search and no clicks is enough to get to a setting. */
        TextField find = new TextField();
        find.setPromptText("Find a setting");
        find.getStyleClass().add("settings-search");
        find.textProperty().addListener((o, was, now) -> {
            String selected = tree.getSelectionModel().getSelectedItem() == null ? null
                    : pathOf(tree.getSelectionModel().getSelectedItem());
            nodes.clear();
            tree.setRoot(fill(new TreeItem<>("Settings"), nodes, now));
            // Stay where we were when it survived the filter, so that typing another
            // letter does not move the page out from under somebody who is reading it.
            TreeItem<String> keep = selected == null ? null : nodes.get(selected);
            tree.getSelectionModel().select(keep != null ? keep
                    : nodes.values().stream().findFirst().orElse(null));
        });
        BorderPane left = new BorderPane(tree);
        left.setTop(find);
        BorderPane.setMargin(find, new Insets(6, 6, 6, 6));

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

        SplitPane split = new SplitPane(left, content);
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

    /**
     * Builds the page tree, keeping only the pages a search matches.
     *
     * <p>A page matches on its path and on the keywords it declares. A branch is kept when
     * any page under it matches, so filtering never leaves a page stranded without the
     * headings it sits beneath.
     *
     * @param nodes filled in with every path that survived, so the caller can select one
     * @return the root, for the tree to be given
     */
    private TreeItem<String> fill(TreeItem<String> root, Map<String, TreeItem<String>> nodes,
                                  String query) {
        for (Map.Entry<String, SettingsPage> entry : pages.entrySet()) {
            String path = entry.getKey();
            if (!Search.matchesAny(query, path, entry.getValue().keywords())) {
                continue;
            }
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
        return root;
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
            public String keywords() {
                return "dark light theme colour color font scaling interface size zoom"
                        + " explorer autoscroll project tool window reopen restore session";
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

                /* Windows and macOS hand the toolkit their scaling factor; Linux does not,
                   so the window comes out at 1:1 next to a desktop drawn at 150% and reads
                   as "the fonts are broken". Auto follows GDK_SCALE when the desktop sets
                   it, and this is for when it does not. */
                ChoiceBox<String> scale = new ChoiceBox<>();
                scale.getItems().addAll("Auto", "100%", "125%", "150%", "175%", "200%");
                String saved = editor.staged().get(com.smide.ui.UiScale.KEY, "Auto");
                scale.setValue(scale.getItems().contains(saved) ? saved : "Auto");
                scale.valueProperty().addListener((o, a, b) ->
                        editor.staged().set(com.smide.ui.UiScale.KEY, b == null ? "Auto" : b));
                HBox scaleRow = new HBox(8, new Label("Interface size"), scale);
                scaleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                Label scaleNote = new Label("How large the whole interface is drawn. Applies as"
                        + " soon as you press Apply, and is where Ctrl+0 goes back to. Auto is"
                        + " smIDE's own default of " + com.smide.ui.Zoom.percent(
                                com.smide.ui.Zoom.DEFAULT) + ", on top of whatever scaling your"
                        + " desktop already does.");
                scaleNote.getStyleClass().add("settings-note");
                scaleNote.setWrapText(true);

                return new VBox(8, dark, autoscroll, restore, scaleRow, scaleNote);
            }
        });
        pages.put("Editor", new SettingsPage() {
            @Override
            public String path() {
                return "Editor";
            }

            @Override
            public String keywords() {
                return "font family size wrap soft-wrap long lines caret line highlight"
                        + " editor.fontFamily editor.fontSize editor.wrap";
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
            public String keywords() {
                return "shortcut shortcuts key keys keyboard keybinding keybindings binding"
                        + " bindings ctrl cmd command alt shift meta";
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
                javafx.collections.ObservableList<Row> all =
                        javafx.collections.FXCollections.observableArrayList();
                for (Action a : ide.actions().all()) {
                    String current = editor.staged().get("keymap." + a.id(), a.shortcut() == null ? "" : a.shortcut());
                    all.add(new Row(a.id(), a.text(), new javafx.beans.property.SimpleStringProperty(current)));
                }
                all.sort((x, y) -> x.text().compareToIgnoreCase(y.text()));

                /* Searched by the keys you press as well as by the name of the action, and
                   that is the whole reason this box is here. The two questions a keymap is
                   ever asked are "what is bound to this action" and "what does this chord
                   already do", and the second one is unanswerable in a sorted list of every
                   action in the IDE. Typing ctrl+shift+n answers it. */
                javafx.collections.transformation.FilteredList<Row> shown =
                        new javafx.collections.transformation.FilteredList<>(all, row -> true);
                TextField findAction = new TextField();
                findAction.setPromptText("Find an action, or the keys it is bound to");
                findAction.getStyleClass().add("settings-search");
                findAction.textProperty().addListener((o, was, now) -> shown.setPredicate(row ->
                        Search.matchesAny(now, row.text(), row.id(),
                                ide.actions().byId(row.id()).map(Action::menuPath).orElse(""),
                                Search.shortcutTerms(row.shortcut().get()))));
                table.setItems(shown);
                table.setPrefHeight(480);
                Label note = new Label("Modifiers: shortcut (Ctrl, or Cmd on macOS), ctrl, alt, shift, meta. Keys as JavaFX names: F10, SLASH, UP.");
                note.getStyleClass().add("settings-note");
                return new VBox(8, findAction, table, note);
            }
        });
        pages.put("Plugins", new SettingsPage() {
            @Override
            public String path() {
                return "Plugins";
            }

            @Override
            public String keywords() {
                return "plugin plugins extension extensions enable disable installed";
            }

            @Override
            public Node create(SettingsEditor editor) {
                VBox box = new VBox(6);
                List<String> disabled = new ArrayList<>(editor.staged().getList("plugins.disabled"));
                /* Each plugin's two rows and the text they are found by, kept together so
                   that filtering hides a plugin's description with its checkbox rather
                   than leaving one of them behind. */
                record Entry(VBox rows, String terms) {
                }
                List<Entry> entries = new ArrayList<>();
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
                    VBox rows = new VBox(2, cb, desc);
                    entries.add(new Entry(rows, Search.haystack(p.descriptor().name(),
                            p.descriptor().id(), p.descriptor().description(),
                            p.descriptor().vendor())));
                }

                /* Twenty-odd plugins is already more than fits on the page, and the name
                   somebody searches for is as often part of the id - "langgraph" - as it is
                   the display name, so both are searched, with the description and vendor. */
                TextField findPlugin = new TextField();
                findPlugin.setPromptText("Find a plugin");
                findPlugin.getStyleClass().add("settings-search");
                Label nothing = new Label("No plugin matches that.");
                nothing.getStyleClass().add("settings-note");
                Runnable refilter = () -> {
                    box.getChildren().clear();
                    entries.stream()
                            .filter(entry -> Search.matches(findPlugin.getText(), entry.terms()))
                            .forEach(entry -> box.getChildren().add(entry.rows()));
                    if (box.getChildren().isEmpty()) {
                        box.getChildren().add(nothing);
                    }
                };
                findPlugin.textProperty().addListener((o, was, now) -> refilter.run());
                refilter.run();

                Label note = new Label("Enabling or disabling a plugin takes effect after a restart."
                        + " Plugins are built into smIDE; there is nothing to install separately.");
                note.getStyleClass().add("settings-note");
                return new VBox(8, findPlugin, box, note);
            }
        });
    }
}
