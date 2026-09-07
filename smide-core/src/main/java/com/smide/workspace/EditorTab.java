package com.smide.workspace;

import com.smide.api.editor.Editor;
import com.smide.ui.Icons;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;

import java.nio.file.Path;

/** A document tab and the editor inside it. */
public final class EditorTab {

    private final Tab tab = new Tab();
    private final Editor editor;

    public EditorTab(Editor editor, String iconLiteral) {
        this.editor = editor;
        tab.setContent(editor.node());
        tab.setUserData(this);
        Node icon = Icons.of(iconLiteral, 13);
        if (icon != null) {
            tab.setGraphic(icon);
        }
        updateLabel();
        editor.modifiedProperty().addListener((obs, was, now) -> updateLabel());
    }

    public Tab tab() {
        return tab;
    }

    public Editor editor() {
        return editor;
    }

    public void updateLabel() {
        Path path = editor.path();
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        tab.setText(editor.isModified() ? name + " •" : name);
        tab.setTooltip(new Tooltip(path.toString()));
        if (editor.isModified()) {
            if (!tab.getStyleClass().contains("tab-modified")) {
                tab.getStyleClass().add("tab-modified");
            }
        } else {
            tab.getStyleClass().remove("tab-modified");
        }
    }
}
