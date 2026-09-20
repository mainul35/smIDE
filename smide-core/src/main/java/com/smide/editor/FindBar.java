package com.smide.editor;

import com.smide.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find and replace, docked above the editor. Every match is marked in the text; Enter
 * and Shift+Enter step through them; Escape closes. Replace All is one edit so it undoes
 * in one step.
 */
public final class FindBar extends VBox {

    private final CodeEditor editor;
    private final CodeArea area;
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final ToggleButton matchCase = new ToggleButton("Aa");
    private final ToggleButton regex = new ToggleButton(".*");
    private final ToggleButton wholeWord = new ToggleButton("W");
    private final Label count = new Label();
    private final HBox replaceRow;
    private List<int[]> matches = new ArrayList<>();
    private int current = -1;

    public FindBar(CodeEditor editor) {
        this.editor = editor;
        this.area = editor.area();
        getStyleClass().add("find-bar");

        findField.setPromptText("Find");
        replaceField.setPromptText("Replace");
        matchCase.setTooltip(com.smide.api.ui.Tooltips.of("Match case"));
        regex.setTooltip(com.smide.api.ui.Tooltips.of("Regular expression"));
        wholeWord.setTooltip(com.smide.api.ui.Tooltips.of("Whole words"));
        for (ToggleButton t : List.of(matchCase, regex, wholeWord)) {
            t.setFocusTraversable(false);
            t.selectedProperty().addListener((o, a, b) -> search(true));
        }
        count.getStyleClass().add("muted-small");

        Button prev = Icons.button("fth-chevron-up", "Previous (Shift+Enter)", () -> step(false));
        Button next = Icons.button("fth-chevron-down", "Next (Enter)", () -> step(true));
        Button close = Icons.button("fth-x", "Close (Esc)", this::hideBar);

        HBox findRow = new HBox(5, new Label("Find"), findField, matchCase, regex, wholeWord, prev, next, count, close);
        findRow.setAlignment(Pos.CENTER_LEFT);

        Button replaceOne = new Button("Replace");
        replaceOne.setOnAction(e -> replaceCurrent());
        Button replaceAll = new Button("Replace all");
        replaceAll.setOnAction(e -> replaceAll());
        replaceRow = new HBox(5, new Label("Replace"), replaceField, replaceOne, replaceAll);
        replaceRow.setAlignment(Pos.CENTER_LEFT);
        replaceRow.setVisible(false);
        replaceRow.setManaged(false);

        setSpacing(4);
        getChildren().addAll(findRow, replaceRow);

        findField.textProperty().addListener((o, a, b) -> search(true));
        findField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                step(!e.isShiftDown());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hideBar();
                e.consume();
            }
        });
        replaceField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                replaceCurrent();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hideBar();
                e.consume();
            }
        });
    }

    public void show(boolean withReplace, String initial) {
        setVisible(true);
        setManaged(true);
        replaceRow.setVisible(withReplace);
        replaceRow.setManaged(withReplace);
        if (initial != null && !initial.isBlank() && !initial.contains("\n")) {
            findField.setText(initial);
        }
        findField.requestFocus();
        findField.selectAll();
        search(true);
    }

    public void hideBar() {
        setVisible(false);
        setManaged(false);
        matches = new ArrayList<>();
        current = -1;
        editor.setSearchHits(List.of());
        area.requestFocus();
    }

    private Pattern pattern() {
        String query = findField.getText();
        if (query == null || query.isEmpty()) {
            return null;
        }
        int flags = matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        String source = regex.isSelected() ? query : Pattern.quote(query);
        if (wholeWord.isSelected()) {
            source = "\\b(?:" + source + ")\\b";
        }
        try {
            return Pattern.compile(source, flags);
        } catch (PatternSyntaxException e) {
            return null;
        }
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
        List<EditorStyles.Overlay> hits = new ArrayList<>(matches.size());
        for (int[] range : matches) {
            hits.add(new EditorStyles.Overlay(range[0], range[1], "search-hit"));
        }
        editor.setSearchHits(hits);
        if (matches.isEmpty()) {
            current = -1;
            count.setText(findField.getText().isEmpty() ? "" : "No matches");
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

    private void replaceCurrent() {
        if (current < 0 || current >= matches.size()) {
            search(true);
            if (current < 0) {
                return;
            }
        }
        int[] range = matches.get(current);
        String replacement = replacement(range);
        area.replaceText(range[0], range[1], replacement);
        search(true);
    }

    private String replacement(int[] range) {
        String text = replaceField.getText() == null ? "" : replaceField.getText();
        if (regex.isSelected()) {
            Pattern p = pattern();
            if (p != null) {
                Matcher m = p.matcher(area.getText(range[0], range[1]));
                if (m.matches()) {
                    try {
                        return m.replaceFirst(text);
                    } catch (RuntimeException ignored) {
                        return text;
                    }
                }
            }
        }
        return text;
    }

    private void replaceAll() {
        if (matches.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        String source = area.getText();
        int pos = 0;
        for (int[] range : matches) {
            sb.append(source, pos, range[0]).append(replacement(range));
            pos = range[1];
        }
        sb.append(source.substring(pos));
        int caret = area.getCaretPosition();
        area.replaceText(sb.toString());
        area.moveTo(Math.min(caret, area.getLength()));
        search(true);
    }
}
