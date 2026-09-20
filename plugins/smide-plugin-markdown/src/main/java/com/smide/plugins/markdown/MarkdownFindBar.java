package com.smide.plugins.markdown;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import org.fxmisc.richtext.CodeArea;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small find field docked above the raw editor, in the spirit of the core's find bar:
 * every match is marked in the text, Enter and Shift+Enter step through them, the
 * {@code Aa} toggle matches case, Escape closes. The core's own Find action targets its
 * own editor only, so the Markdown editor wires Ctrl+F to this itself.
 */
final class MarkdownFindBar extends HBox {

    private final MarkdownEditor editor;
    private final CodeArea area;
    private final TextField field = new TextField();
    private final ToggleButton matchCase = new ToggleButton("Aa");
    private final Label count = new Label();
    private List<int[]> matches = new ArrayList<>();
    private int current = -1;

    MarkdownFindBar(MarkdownEditor editor, CodeArea area) {
        this.editor = editor;
        this.area = area;
        getStyleClass().add("find-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(5);

        field.setPromptText("Find");
        matchCase.setTooltip(com.smide.api.ui.Tooltips.of("Match case"));
        matchCase.setFocusTraversable(false);
        matchCase.selectedProperty().addListener((o, a, b) -> search(true));
        count.getStyleClass().add("muted-small");

        Button prev = iconButton("fth-chevron-up", "Previous (Shift+Enter)", () -> step(false));
        Button next = iconButton("fth-chevron-down", "Next (Enter)", () -> step(true));
        Button close = iconButton("fth-x", "Close (Esc)", this::hideBar);
        getChildren().addAll(new Label("Find"), field, matchCase, prev, next, count, close);

        field.textProperty().addListener((o, a, b) -> search(true));
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                step(!e.isShiftDown());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hideBar();
                e.consume();
            }
        });
        setVisible(false);
        setManaged(false);
    }

    private static Button iconButton(String literal, String tooltip, Runnable onAction) {
        Button button = new Button();
        Node icon = null;
        try {
            FontIcon fi = new FontIcon(literal);
            fi.setIconSize(14);
            icon = fi;
        } catch (RuntimeException e) {
            // Icon pack not on the class path; fall back to text.
        }
        if (icon != null) {
            button.setGraphic(icon);
        } else {
            button.setText(tooltip);
        }
        button.getStyleClass().add("icon-button");
        button.setTooltip(com.smide.api.ui.Tooltips.of(tooltip));
        button.setFocusTraversable(false);
        button.setOnAction(e -> onAction.run());
        return button;
    }

    void show(String initial) {
        setVisible(true);
        setManaged(true);
        if (initial != null && !initial.isBlank() && !initial.contains("\n")) {
            field.setText(initial);
        }
        field.requestFocus();
        field.selectAll();
        search(true);
    }

    void hideBar() {
        setVisible(false);
        setManaged(false);
        matches = new ArrayList<>();
        current = -1;
        editor.setSearchHits(List.of());
        area.requestFocus();
    }

    private Pattern pattern() {
        String query = field.getText();
        if (query == null || query.isEmpty()) {
            return null;
        }
        int flags = matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(Pattern.quote(query), flags);
    }

    private void search(boolean fromCaret) {
        matches = new ArrayList<>();
        Pattern p = pattern();
        if (p != null) {
            Matcher m = p.matcher(area.getText());
            while (m.find()) {
                if (m.end() > m.start()) {
                    matches.add(new int[]{m.start(), m.end()});
                }
                if (matches.size() > 10_000) {
                    break;
                }
            }
        }
        List<Spans.Overlay> hits = new ArrayList<>(matches.size());
        for (int[] range : matches) {
            hits.add(new Spans.Overlay(range[0], range[1], "search-hit"));
        }
        editor.setSearchHits(hits);
        if (matches.isEmpty()) {
            current = -1;
            count.setText(field.getText().isEmpty() ? "" : "No matches");
            return;
        }
        if (fromCaret) {
            int caret = area.getSelection().getStart();
            current = 0;
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i)[0] >= caret) {
                    current = i;
                    break;
                }
            }
            selectCurrent();
        }
    }

    private void step(boolean forward) {
        if (matches.isEmpty()) {
            search(true);
            return;
        }
        current = forward ? (current + 1) % matches.size() : (current - 1 + matches.size()) % matches.size();
        selectCurrent();
    }

    private void selectCurrent() {
        if (current < 0 || current >= matches.size()) {
            return;
        }
        int[] range = matches.get(current);
        area.selectRange(range[0], range[1]);
        area.requestFollowCaret();
        count.setText((current + 1) + " of " + matches.size());
    }
}
