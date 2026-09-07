package com.smide.plugins.database;

import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/**
 * The Database tool window: the connections, what is inside them, and a way in.
 *
 * <p>The tree fills itself as it is opened - schemas when a connection is expanded,
 * tables when a schema is, columns when a table is - because asking a server for
 * everything up front is slow and almost all of it is never looked at.
 */
public final class DatabaseToolWindow implements ToolWindowFactory {

    public static final String ID = "database";

    private final DatabaseUi ui;
    private final TreeView<Object> tree = new TreeView<>();
    private final TreeItem<Object> root = new TreeItem<>("Databases");
    private final Label empty = new Label("No connections yet.");
    private final Button addFirst = new Button("Add a database connection");
    private final StackPane pane = new StackPane();
    private BorderPane content;
    private ToolWindowContext context;

    public DatabaseToolWindow(DatabaseUi ui) {
        this.ui = ui;
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setCellFactory(v -> new NodeCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                open(selected());
            }
        });
        empty.getStyleClass().add("empty-hint");
        addFirst.setOnAction(e -> add());
    }

    // -------------------------------------------------------------- the tree

    /** Rebuilds the top level from the saved connections, keeping nothing expanded. */
    public void refresh() {
        root.getChildren().clear();
        for (DataSource source : ui.databases().sources()) {
            root.getChildren().add(connectionNode(source));
        }
        boolean any = !root.getChildren().isEmpty();
        pane.getChildren().get(0).setVisible(!any);
        pane.getChildren().get(1).setVisible(any);
    }

    private TreeItem<Object> connectionNode(DataSource source) {
        TreeItem<Object> item = new TreeItem<>(source);
        item.getChildren().add(new TreeItem<>("Loading..."));
        item.expandedProperty().addListener((o, was, now) -> {
            if (now && isPlaceholder(item)) {
                ui.withPassword(source, () -> ui.withProgress("Connecting to " + source.name(),
                        () -> ui.databases().schemas(source),
                        schemas -> {
                            item.getChildren().clear();
                            for (String schema : schemas) {
                                item.getChildren().add(schemaNode(source, schema));
                            }
                            tree.refresh();
                        }));
            }
        });
        return item;
    }

    private TreeItem<Object> schemaNode(DataSource source, String schema) {
        TreeItem<Object> item = new TreeItem<>(new Schema(source, schema));
        item.getChildren().add(new TreeItem<>("Loading..."));
        item.expandedProperty().addListener((o, was, now) -> {
            if (now && isPlaceholder(item)) {
                ui.read(() -> ui.databases().tables(source, schema), tables -> {
                    item.getChildren().clear();
                    for (Databases.Table table : tables) {
                        item.getChildren().add(tableNode(source, table));
                    }
                    if (tables.isEmpty()) {
                        item.getChildren().add(new TreeItem<>("No tables"));
                    }
                });
            }
        });
        return item;
    }

    private TreeItem<Object> tableNode(DataSource source, Databases.Table table) {
        TreeItem<Object> item = new TreeItem<>(table);
        item.getChildren().add(new TreeItem<>("Loading..."));
        item.expandedProperty().addListener((o, was, now) -> {
            if (now && isPlaceholder(item)) {
                ui.read(() -> ui.databases().columns(source, table), columns -> {
                    item.getChildren().clear();
                    for (Databases.Column column : columns) {
                        item.getChildren().add(new TreeItem<>(column));
                    }
                });
            }
        });
        return item;
    }

    private static boolean isPlaceholder(TreeItem<Object> item) {
        return item.getChildren().size() == 1 && "Loading...".equals(item.getChildren().get(0).getValue());
    }

    private Object selected() {
        TreeItem<Object> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    /** The connection a node belongs to, whatever depth it is at. */
    private DataSource sourceOf(Object value) {
        if (value instanceof DataSource source) {
            return source;
        }
        if (value instanceof Schema schema) {
            return schema.source();
        }
        TreeItem<Object> item = tree.getSelectionModel().getSelectedItem();
        while (item != null) {
            if (item.getValue() instanceof DataSource source) {
                return source;
            }
            if (item.getValue() instanceof Schema schema) {
                return schema.source();
            }
            item = item.getParent();
        }
        return null;
    }

    // ------------------------------------------------------------- commands

    private void add() {
        new ConnectionDialog(ui).show(null, source -> {
            ui.databases().add(source);
            refresh();
        });
    }

    private void edit() {
        if (selected() instanceof DataSource source) {
            new ConnectionDialog(ui).show(source, updated -> {
                ui.databases().replace(source, updated);
                refresh();
            });
        }
    }

    private void remove() {
        if (selected() instanceof DataSource source
                && ui.ide().window().confirm("Remove connection",
                "Remove " + source.name() + "? The database itself is not touched.")) {
            ui.databases().remove(source);
            refresh();
        }
    }

    /** Double-click, or the menu: a table shows its first rows, a connection opens a console. */
    private void open(Object value) {
        DataSource source = sourceOf(value);
        if (source == null) {
            return;
        }
        if (value instanceof Databases.Table table) {
            /* Quoted by the server's own rules - backticks on MySQL, double quotes on
               PostgreSQL - so a table with an awkward name still opens. */
            ui.read(() -> ui.databases().qualify(source, table), qualified ->
                    new QueryConsole(ui, source).show("SELECT * FROM " + qualified + " LIMIT 100;", true));
        } else if (value instanceof DataSource || value instanceof Schema) {
            String schema = value instanceof Schema s ? "USE `" + s.name() + "`;\n" : "";
            ui.withPassword(source, () -> new QueryConsole(ui, source).show(schema));
        }
    }

    private void disconnect() {
        if (selected() instanceof DataSource source) {
            ui.databases().close(source);
            ui.ide().statusBar().message("Disconnected from " + source.name());
            refresh();
        }
    }

    private ContextMenu menu() {
        ContextMenu menu = new ContextMenu();
        MenuItem console = new MenuItem("Open Query Console");
        console.setOnAction(e -> open(selected()));
        MenuItem edit = new MenuItem("Edit Connection...");
        edit.setOnAction(e -> edit());
        MenuItem drop = new MenuItem("Remove Connection");
        drop.setOnAction(e -> remove());
        MenuItem close = new MenuItem("Disconnect");
        close.setOnAction(e -> disconnect());
        MenuItem refresh = new MenuItem("Refresh");
        refresh.setOnAction(e -> refresh());
        menu.getItems().addAll(console, new SeparatorMenuItem(), edit, drop, close,
                new SeparatorMenuItem(), refresh);
        return menu;
    }

    private Node toolbar() {
        HBox bar = new HBox(4,
                icon("fth-plus", "New connection", this::add),
                icon("fth-edit-2", "Edit connection", this::edit),
                icon("fth-minus", "Remove connection", this::remove),
                spacer(),
                icon("fth-terminal", "Query console", () -> open(selected())),
                icon("fth-refresh-cw", "Refresh", this::refresh));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 6, 4, 6));
        return bar;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static Button icon(String literal, String tip, Runnable action) {
        Button button = new Button();
        button.setGraphic(new FontIcon(literal));
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    // ------------------------------------------------------ ToolWindowFactory

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Database";
    }

    @Override
    public String iconLiteral() {
        return "fth-database";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.RIGHT;
    }

    @Override
    public String shortcut() {
        return "alt+8";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public Node create(ToolWindowContext context) {
        this.context = context;
        if (content == null) {
            content = new BorderPane(tree);
            content.setTop(toolbar());
            tree.setContextMenu(menu());
            VBox nothing = new VBox(10, empty, addFirst);
            nothing.setAlignment(Pos.CENTER);
            pane.getChildren().addAll(nothing, content);
        }
        refresh();
        return pane;
    }

    /** A schema inside a connection; the tree needs it to know which server it came from. */
    private record Schema(DataSource source, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final class NodeCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            if (empty || value == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String icon = switch (value) {
                case DataSource ignored -> "fth-server";
                case Schema ignored -> "fth-database";
                case Databases.Table table -> table.view() ? "fth-eye" : "fth-grid";
                case Databases.Column column -> column.primaryKey() ? "fth-key" : "fth-minus";
                default -> null;
            };
            setGraphic(icon == null ? null : new FontIcon(icon));
            if (value instanceof Databases.Column column) {
                setText(column.name() + "    " + column.describe());
            } else if (value instanceof DataSource source) {
                setText(source.name() + "    " + source.describe());
            } else {
                setText(String.valueOf(value));
            }
        }
    }
}
