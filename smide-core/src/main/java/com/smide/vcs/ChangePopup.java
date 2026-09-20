package com.smide.vcs;

import com.smide.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/**
 * What a changed line looked like when it was last committed, shown where it changed.
 *
 * <p>Clicking the strip beside the code opens this, as it does in IntelliJ: the lines the commit
 * has, in the editor's own font, with what can be done about them - put them back, copy them, or
 * step to the change before or after this one. A line that was only added has nothing to show, so
 * it says so rather than opening an empty box.
 */
public final class ChangePopup {

    private Popup popup;

    /** Closes whatever is open, if anything. */
    public void hide() {
        if (popup != null) {
            popup.hide();
            popup = null;
        }
    }

    public boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    /**
     * Shows one change.
     *
     * @param owner      the window to hang the popup off
     * @param stylesheet the theme, so the popup is the same colours as the window
     * @param dark       whether the dark theme is on
     * @param hunk       the change under the pointer
     * @param committed  the whole file as it was committed
     * @param at         where on the screen to put it
     * @param rollback   puts the committed lines back
     * @param step       moves to the previous (-1) or next (1) change
     */
    public void show(Window owner, String stylesheet, boolean dark,
                     LineChanges.Hunk hunk, String committed, double[] at,
                     Runnable rollback, Consumer<Integer> step) {
        hide();
        List<String> lines = hunk.committedLines(committed);
        VBox panel = new VBox(6);
        panel.getStyleClass().add("change-popup");
        if (dark) {
            panel.getStyleClass().add("dark-theme");
        }
        panel.setPadding(new Insets(8));
        panel.getChildren().add(toolbar(hunk, lines, rollback, step));
        panel.getChildren().add(body(hunk, lines));
        panel.setMaxWidth(760);

        Popup showing = new Popup();
        showing.setAutoHide(true);
        showing.setHideOnEscape(true);
        showing.getContent().add(panel);
        showing.getScene().getStylesheets().add(stylesheet);
        showing.getScene().setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
            }
        });
        showing.show(owner, at[0], at[1]);
        popup = showing;
    }

    private HBox toolbar(LineChanges.Hunk hunk, List<String> lines, Runnable rollback, Consumer<Integer> step) {
        Button previous = button("fth-chevron-up", "Previous change", () -> step.accept(-1));
        Button next = button("fth-chevron-down", "Next change", () -> step.accept(1));
        Button revert = button("fth-rotate-ccw", "Put the committed lines back", () -> {
            rollback.run();
            hide();
        });
        Button copy = button("fth-copy", "Copy the committed lines", () -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join("\n", lines));
            Clipboard.getSystemClipboard().setContent(content);
            hide();
        });
        copy.setDisable(lines.isEmpty());
        Label what = new Label(describe(hunk));
        what.getStyleClass().add("change-popup-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(2, previous, next, revert, copy, spacer, what);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static String describe(LineChanges.Hunk hunk) {
        int lines = hunk.end() - hunk.start();
        return switch (hunk.kind()) {
            case ADDED -> lines == 1 ? "1 line added" : lines + " lines added";
            case CHANGED -> lines == 1 ? "1 line changed" : lines + " lines changed";
            case REMOVED -> {
                int gone = hunk.baseEnd() - hunk.baseStart();
                yield gone == 1 ? "1 line removed" : gone + " lines removed";
            }
        };
    }

    /** The committed lines themselves, or a word about why there are none. */
    private static Region body(LineChanges.Hunk hunk, List<String> lines) {
        if (lines.isEmpty()) {
            Label none = new Label(hunk.kind() == LineChanges.Kind.ADDED
                    ? "These lines are new - the last commit has nothing here."
                    : "The last commit has nothing here.");
            none.getStyleClass().add("change-popup-empty");
            return new VBox(none);
        }
        Label text = new Label(String.join("\n", lines));
        text.getStyleClass().add("change-popup-text");
        ScrollPane scroll = new ScrollPane(text);
        scroll.getStyleClass().add("change-popup-scroll");
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(lines.size(), 12) * 18.0 + 8);
        scroll.setMaxHeight(260);
        return scroll;
    }

    private static Button button(String icon, String tip, Runnable action) {
        Button button = Icons.button(icon, tip, action);
        button.getStyleClass().add("change-popup-button");
        return button;
    }

}
