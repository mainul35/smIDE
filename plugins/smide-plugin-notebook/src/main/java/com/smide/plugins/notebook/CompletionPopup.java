package com.smide.plugins.notebook;

import com.smide.api.Ide;
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
    /** Run after an accepted item has been inserted; see {@link #setAfterInsert}. */
    private Runnable afterInsert = () -> { };

    CompletionPopup(Ide ide) {
        /* Not focus-traversable, and this is the whole of why Enter works.
           A Popup is a window of its own, and key events go to the focused node of the
           focused window. A focus-traversable list inside the popup becomes that node the
           moment the popup is shown, so Enter and the arrow keys are delivered to the
           list - which does nothing with them - and never reach the cell's handler, where
           this class is actually asked about them. The list is still selectable and still
           clickable; it simply never takes the keyboard. */
        list.setFocusTraversable(false);
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
        // A Popup carries a scene of its own, so the application stylesheet and the dark
        // class have to be put on this subtree or the list is drawn in modena's default
        // palette rather than the one the rest of the window is using.
        if (ide != null && ide.theme() != null) {
            list.getStylesheets().add(ide.theme().stylesheet());
            ide.theme().style(list);
        }
        popup.getContent().add(list);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        // Nudged back on screen rather than drawn off the bottom edge for a cell near it.
        popup.setAutoFix(true);
    }

    /**
     * What to do once an accepted item has been put into the cell.
     *
     * <p>Inserting text is an edit like any other, and the editor answers an edit by
     * asking for suggestions again - so accepting a completion put the list straight back
     * up over the word it had just finished. The editor cancels that here, after the
     * insertion rather than before, because the insertion is what schedules it.
     */
    void setAfterInsert(Runnable afterInsert) {
        this.afterInsert = afterInsert == null ? () -> { } : afterInsert;
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
        afterInsert.run();
    }
}
