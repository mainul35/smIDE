package com.smide.debug;

import com.smide.api.debug.DebugSession;
import com.smide.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Variables as a tree: one row each, with the objects and arrays among them opening
 * onto their fields and elements.
 *
 * <p>Shared by the Debug window and the value popup in the editor, so a value looks and
 * opens the same wherever it is looked at. Children are fetched the first time a row is
 * opened - every one is a round trip to the program - and only while it is stopped.
 */
public final class VariableTree {

    private final Supplier<DebugSession> session;
    private final Predicate<TreeItem<DebugSession.VariableInfo>> watched;

    /**
     * @param session the session children are read from, asked for each time
     * @param watched which rows are the reader's watched expressions, drawn apart
     */
    public VariableTree(Supplier<DebugSession> session,
                        Predicate<TreeItem<DebugSession.VariableInfo>> watched) {
        this.session = session;
        this.watched = watched;
    }

    /** Gives a tree this class's rows. */
    public void install(TreeView<DebugSession.VariableInfo> tree) {
        tree.setCellFactory(v -> new VariableCell());
    }

    /** A variable node that fetches its children the first time it is opened. */
    public TreeItem<DebugSession.VariableInfo> node(DebugSession.VariableInfo variable) {
        TreeItem<DebugSession.VariableInfo> item = new TreeItem<>(variable) {
            @Override
            public boolean isLeaf() {
                return !variable.expandable();
            }
        };
        if (variable.expandable()) {
            // A placeholder child, so the row shows an arrow before anything is fetched.
            item.getChildren().add(new TreeItem<>());
            item.expandedProperty().addListener((o, was, now) -> {
                DebugSession current = session.get();
                if (now && current != null && current.isSuspended()
                        && item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null) {
                    item.getChildren().clear();
                    for (DebugSession.VariableInfo child : current.children(variable)) {
                        item.getChildren().add(node(child));
                    }
                }
            });
        }
        return item;
    }

    private final class VariableCell extends TreeCell<DebugSession.VariableInfo> {
        @Override
        protected void updateItem(DebugSession.VariableInfo variable, boolean empty) {
            super.updateItem(variable, empty);
            getStyleClass().remove("debug-watch");
            if (empty || variable == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            // A watched expression is the reader's own row, not one of the frame's.
            boolean isWatch = watched.test(getTreeItem());
            if (isWatch) {
                getStyleClass().add("debug-watch");
            }
            Label name = new Label(variable.name());
            Label value = new Label(variable.value());
            value.getStyleClass().add("debug-value");
            Label type = new Label(variable.type() == null ? "" : variable.type());
            type.getStyleClass().add("debug-type");
            HBox row = new HBox(8, name, new Label("="), value, type);
            if (isWatch) {
                row.getChildren().add(0, Icons.of("fth-eye", 11));
            }
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }
}
