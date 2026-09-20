package com.smide.vcs;

import com.smide.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
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
 * What a changed line was when it was last committed, shown where it changed.
 *
 * <p>Clicking the strip beside the code opens this, as it does in IntelliJ: a row of things to do
 * with the change, a field for committing that one change on its own, and underneath, the lines
 * the last commit has, drawn as removed. A stretch that is only new has nothing to show below the
 * toolbar, so it says so rather than opening an empty box.
 */
public final class ChangePopup {

    /** What the popup can do, given by whoever opens it. */
    public record Actions(Runnable rollback,
                          Consumer<Integer> step,
                          Runnable showDiff,
                          Consumer<String> commit,
                          boolean canCommit) {
    }

    private Popup popup;

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
     */
    public void show(Window owner, String stylesheet, boolean dark,
                     LineChanges.Hunk hunk, String committed, double[] at, Actions actions) {
        hide();
        List<String> lines = hunk.committedLines(committed);
        VBox panel = new VBox(4);
        panel.getStyleClass().add("change-popup");
        if (dark) {
            panel.getStyleClass().add("dark-theme");
        }
        panel.setPadding(new Insets(6));
        panel.getChildren().add(toolbar(hunk, lines, actions));
        if (lines.isEmpty()) {
            Label none = new Label("These lines are new - the last commit has nothing here.");
            none.getStyleClass().add("change-popup-empty");
            panel.getChildren().add(none);
        } else {
            panel.getChildren().add(body(lines));
        }
        panel.setMaxWidth(820);

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

    /**
     * The row along the top: what can be done with this change, then the field that commits it.
     *
     * <p>The same order IntelliJ uses, because it is the order they are reached for: step through
     * the changes, put this one back, see it beside the commit, copy what was there.
     */
    private HBox toolbar(LineChanges.Hunk hunk, List<String> lines, Actions actions) {
        Button previous = button("fth-chevron-up", "Previous change", () -> actions.step().accept(-1));
        Button next = button("fth-chevron-down", "Next change", () -> actions.step().accept(1));
        Button revert = button("fth-rotate-ccw", "Put the committed lines back", () -> {
            actions.rollback().run();
            hide();
        });
        Button diff = button("fth-columns", "Compare this file with the commit", () -> {
            hide();
            actions.showDiff().run();
        });
        Button copy = button("fth-copy", "Copy the committed lines", () -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join("\n", lines));
            Clipboard.getSystemClipboard().setContent(content);
            hide();
        });
        copy.setDisable(lines.isEmpty());

        TextField message = new TextField();
        message.getStyleClass().add("change-popup-message");
        message.setPromptText(actions.canCommit() ? "Commit this change" : "Not in a repository");
        message.setPrefColumnCount(24);
        message.setDisable(!actions.canCommit());
        HBox.setHgrow(message, Priority.ALWAYS);
        Button commit = button("fth-check", "Commit this change on its own", () -> commit(message, actions));
        commit.setDisable(!actions.canCommit());
        message.setOnAction(e -> commit(message, actions));

        Label what = new Label(describe(hunk));
        what.getStyleClass().add("change-popup-title");
        HBox bar = new HBox(2, previous, next, revert, diff, copy,
                new Separator(Orientation.VERTICAL), message, commit, what);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("change-popup-toolbar");
        return bar;
    }

    private void commit(TextField message, ChangePopup.Actions actions) {
        String text = message.getText() == null ? "" : message.getText().strip();
        if (text.isEmpty()) {
            message.requestFocus();
            return;
        }
        hide();
        actions.commit().accept(text);
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

    /** The committed lines, each drawn as what it is: something this file no longer has. */
    private static Region body(List<String> lines) {
        VBox box = new VBox();
        box.getStyleClass().add("change-popup-lines");
        for (String line : lines) {
            Label text = new Label(line.isEmpty() ? " " : line);
            text.getStyleClass().add("change-popup-line");
            text.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().add(text);
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.getStyleClass().add("change-popup-scroll");
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(lines.size(), 12) * 19.0 + 6);
        scroll.setMaxHeight(280);
        return scroll;
    }

    private static Button button(String icon, String tip, Runnable action) {
        Button button = Icons.button(icon, tip, action);
        button.getStyleClass().add("change-popup-button");
        return button;
    }
}
