package com.smide.plugins.notebook;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Popup;
import org.eclipse.lsp4j.CompletionItem;
import org.fxmisc.richtext.CodeArea;

import java.util.List;
import java.util.Optional;

/**
 * The list of suggestions over a cell.
 *
 * <p>Its own popup, and not the IDE's, because the IDE's completion is wired to the IDE's
 * own editor: a plugin's editor is a {@code Node} as far as the core is concerned, and
 * nothing reaches inside it. This is therefore small on purpose - a list, the arrow keys,
 * Enter and Escape - and does only what a reader in a cell needs.
 *
 * <p>What is inserted is the item's {@code insertText} where pyright gave one and its
 * label otherwise, and what it replaces is the word the caret is in, so that completing
 * halfway through a name does not leave the first half behind.
 */
final class CompletionPopup {

    private static final int MAX_SHOWN = 12;

    private final Popup popup = new Popup();
    private final ListView<CompletionItem> list = new ListView<>();
    private CodeArea area;
    /** Where the word being completed starts, so accepting can replace it. */
    private int replaceFrom;

    CompletionPopup() {
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(CompletionItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(item.getLabel());
                name.getStyleClass().add("completion-label");
                Label detail = new Label(item.getDetail() == null
                        ? (item.getKind() == null ? "" : item.getKind().toString())
                        : item.getDetail());
                detail.getStyleClass().add("completion-detail");
                Region gap = new Region();
                HBox.setHgrow(gap, Priority.ALWAYS);
                HBox row = new HBox(6, name, gap, detail);
                setGraphic(row);
                setText(null);
            }
        });
        list.getStyleClass().add("completion-list");
        list.setPrefWidth(420);
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                accept();
            }
        });
        popup.getContent().add(list);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    /**
     * Shows the suggestions for the word starting at an offset.
     *
     * <p>An empty list hides rather than shows an empty box, which is what a reader expects
     * when there is nothing to offer.
     */
    void show(CodeArea over, List<CompletionItem> items, int wordStart) {
        if (items == null || items.isEmpty()) {
            hide();
            return;
        }
        this.area = over;
        this.replaceFrom = wordStart;
        list.getItems().setAll(items);
        list.getSelectionModel().select(0);
        list.setPrefHeight(Math.min(items.size(), MAX_SHOWN) * 24.0 + 4);
        Optional<Bounds> caret = over.getCaretBounds();
        if (caret.isEmpty() || over.getScene() == null || over.getScene().getWindow() == null) {
            hide();
            return;
        }
        Bounds bounds = caret.get();
        popup.show(over, bounds.getMinX(), bounds.getMaxY() + 2);
        // The list must not take the caret: the reader is still typing into the cell.
        Platform.runLater(over::requestFocus);
    }

    void hide() {
        popup.hide();
    }

    /**
     * Handles a key while the popup is up; true when the key was the popup's.
     *
     * <p>Called from the cell's own handler before the {@code CodeArea} sees the event, so
     * that Up and Down move through the list rather than the text.
     */
    boolean handle(KeyEvent event) {
        if (!popup.isShowing()) {
            return false;
        }
        KeyCode code = event.getCode();
        switch (code) {
            case UP -> {
                move(-1);
                return true;
            }
            case DOWN -> {
                move(1);
                return true;
            }
            case PAGE_UP -> {
                move(-MAX_SHOWN);
                return true;
            }
            case PAGE_DOWN -> {
                move(MAX_SHOWN);
                return true;
            }
            case ENTER, TAB -> {
                accept();
                return true;
            }
            case ESCAPE -> {
                hide();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void move(int by) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int at = list.getSelectionModel().getSelectedIndex();
        int to = Math.max(0, Math.min(at + by, size - 1));
        list.getSelectionModel().select(to);
        list.scrollTo(to);
    }

    private void accept() {
        CompletionItem item = list.getSelectionModel().getSelectedItem();
        if (item == null || area == null) {
            hide();
            return;
        }
        String text = item.getInsertText() != null && !item.getInsertText().isBlank()
                ? item.getInsertText() : item.getLabel();
        int caret = area.getCaretPosition();
        int from = Math.max(0, Math.min(replaceFrom, caret));
        hide();
        area.replaceText(from, caret, text);
    }
}
