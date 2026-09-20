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
        tab.setUserData(this);
        /* The editor's node joins the window when this tab is first brought to the front,
           and not before. JavaFX keeps the content of every tab in the scene, selected or
           not, and a node in the scene is styled and laid out whether or not anybody can
           see it - which, restoring a session of ten files, is nine editors' worth of work
           done for nobody. The editor itself is built either way; this is about what the
           window has to draw. */
        tab.selectedProperty().addListener((property, was, selected) -> {
            if (selected) {
                if (tab.getContent() == null) {
                    tab.setContent(editor.node());
                }
                // And what the editor keeps back for a reader, it builds now.
                editor.shown();
            }
        });
        Node icon = Icons.of(iconLiteral, 13);
        if (icon != null) {
            tab.setGraphic(icon);
        }
        updateLabel();
        editor.modifiedProperty().addListener((obs, was, now) -> updateLabel());
    }

    /** The icon again, for a file whose language was worked out after it was opened. */
    public void setIcon(String iconLiteral) {
        Node icon = Icons.of(iconLiteral, 13);
        tab.setGraphic(icon);
    }

    public Tab tab() {
        return tab;
    }

    public Editor editor() {
        return editor;
    }

    /** Errors reported in this tab's file; shown on the tab so a broken file is seen without opening it. */
    private int errors;

    /** Marks the tab as holding a file with this many errors; zero clears it. */
    public void setErrors(int count) {
        if (count == errors) {
            return;
        }
        errors = count;
        updateLabel();
    }

    public void updateLabel() {
        Path path = editor.path();
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        tab.setText(editor.isModified() ? name + " •" : name);
        tab.setTooltip(new Tooltip(errors == 0 ? path.toString()
                : path + "\n" + errors + (errors == 1 ? " error" : " errors")));
        if (errors > 0) {
            if (!tab.getStyleClass().contains("tab-errors")) {
                tab.getStyleClass().add("tab-errors");
            }
        } else {
            tab.getStyleClass().remove("tab-errors");
        }
        if (editor.isModified()) {
            if (!tab.getStyleClass().contains("tab-modified")) {
                tab.getStyleClass().add("tab-modified");
            }
        } else {
            tab.getStyleClass().remove("tab-modified");
        }
    }
}
