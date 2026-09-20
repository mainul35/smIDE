package com.smide.completion;

import com.smide.api.Ide;
import com.smide.api.lang.CompletionSource;
import com.smide.api.lang.CompletionSource.Suggestion;
import com.smide.core.ExtensionRegistry;
import com.smide.editor.CodeEditor;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Completion for a file with no language server behind it: the words a plugin offers for that
 * kind of file - Gradle's build vocabulary in a {@code build.gradle} - and the words already in
 * the file.
 *
 * <p>It knows no types and no scope, and does not pretend to: it is the list a reader would
 * otherwise keep in their head or scroll up to look at. Where a language server is running, it
 * stays out of the way - that one knows more.
 */
public final class WordCompletion {

    /** Words of the file itself: three characters is the shortest worth offering back. */
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final int MAX_ITEMS = 200;

    private final Ide ide;
    private final ExtensionRegistry registry;
    private final CodeEditor editor;
    /** Whether a language server is handling this editor, in which case this does nothing. */
    private final Supplier<Boolean> serverIsHandling;

    private final Popup popup = new Popup();
    private final ListView<Suggestion> list = new ListView<>();
    private final Label detail = new Label();
    private final PauseTransition delay = new PauseTransition(Duration.millis(150));
    private List<Suggestion> all = List.of();
    private int anchor = -1;

    private WordCompletion(Ide ide, ExtensionRegistry registry, CodeEditor editor, Supplier<Boolean> serverIsHandling) {
        this.ide = ide;
        this.registry = registry;
        this.editor = editor;
        this.serverIsHandling = serverIsHandling;

        list.getStyleClass().add("popup-list");
        list.setPrefSize(420, 200);
        list.setFocusTraversable(false);
        list.setCellFactory(v -> new Cell());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                apply();
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, item) ->
                detail.setText(item == null || item.detail() == null ? "" : item.detail()));
        detail.getStyleClass().add("completion-item-detail");
        detail.setWrapText(true);
        detail.setMaxWidth(420);
        VBox panel = new VBox(4, list, detail);
        panel.getStyleClass().add("popup-panel");
        // A popup is a scene of its own: the stylesheet and the theme have to be put on it.
        panel.getStylesheets().add(ide.theme().stylesheet());
        ide.theme().style(panel);
        popup.getContent().add(panel);
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        delay.setOnFinished(e -> show(false));
    }

    /** Watches an editor, and suggests while it is typed in. */
    public static void install(Ide ide, ExtensionRegistry registry, CodeEditor editor, Supplier<Boolean> serverIsHandling) {
        WordCompletion completion = new WordCompletion(ide, registry, editor, serverIsHandling);
        editor.area().addEventFilter(KeyEvent.KEY_PRESSED, completion::onKeyPressed);
        editor.area().addEventHandler(KeyEvent.KEY_TYPED, completion::onKeyTyped);
    }

    private void onKeyPressed(KeyEvent e) {
        if (popup.isShowing() && handleKey(e)) {
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.SPACE && e.isControlDown() && !off()) {
            e.consume();
            show(true);
        }
    }

    private void onKeyTyped(KeyEvent e) {
        String typed = e.getCharacter();
        if (off() || typed == null || typed.isEmpty() || e.isControlDown() || e.isAltDown()) {
            return;
        }
        char c = typed.charAt(0);
        if (Character.isLetterOrDigit(c) || c == '_') {
            if (popup.isShowing()) {
                refilter();
            } else if (ide.settings().getBoolean("editor.autoPopup", true)) {
                delay.playFromStart();
            }
        } else {
            delay.stop();
            hide();
        }
    }

    /** Off where a language server is answering, and in a file that cannot be typed in. */
    private boolean off() {
        return Boolean.TRUE.equals(serverIsHandling.get()) || !editor.area().isEditable() || sources().isEmpty();
    }

    private List<CompletionSource> sources() {
        Path file = editor.path();
        List<CompletionSource> handling = new ArrayList<>();
        for (CompletionSource source : registry.completionSources()) {
            try {
                if (file != null && source.handles(file)) {
                    handling.add(source);
                }
            } catch (RuntimeException e) {
                System.err.println("smIDE: a completion source failed: " + e);
            }
        }
        return handling;
    }

    private void show(boolean explicit) {
        if (off()) {
            return;
        }
        int caret = editor.caretOffset();
        anchor = wordStart(caret);
        String prefix = editor.text().substring(anchor, caret);
        if (!explicit && prefix.length() < 2) {
            return;
        }
        all = gather(prefix);
        if (all.isEmpty()) {
            hide();
            return;
        }
        refilter();
        if (list.getItems().isEmpty()) {
            return;
        }
        /* Below the caret, or at the top of the editor when the caret has no place on screen
           yet - a window that has just opened, a line not laid out. A list that cannot be
           placed exactly is still better than no list. */
        Bounds atCaret = editor.area().getCaretBounds().orElse(null);
        Bounds area = editor.area().localToScreen(editor.area().getBoundsInLocal());
        if (atCaret == null && area == null) {
            return;
        }
        double x = atCaret != null ? atCaret.getMinX() : area.getMinX() + 40;
        double y = atCaret != null ? atCaret.getMaxY() + 2 : area.getMinY() + 40;
        popup.show(editor.area(), x, y);
    }

    /** What the plugins offer for this file, then the words already written in it. */
    private List<Suggestion> gather(String prefix) {
        Map<String, Suggestion> found = new LinkedHashMap<>();
        Path file = editor.path();
        String text = editor.text();
        for (CompletionSource source : sources()) {
            try {
                for (Suggestion suggestion : source.suggest(file, text, editor.caretOffset())) {
                    if (suggestion != null && suggestion.text() != null && !suggestion.text().isBlank()) {
                        found.putIfAbsent(suggestion.text(), suggestion);
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("smIDE: a completion source failed: " + e);
            }
        }
        Matcher words = WORD.matcher(text);
        while (words.find()) {
            // Not the half-typed word under the caret, offered back as if it were a suggestion.
            if (words.start() == anchor) {
                continue;
            }
            found.putIfAbsent(words.group(), new Suggestion(words.group(), null, "word"));
        }
        found.remove(prefix);
        return List.copyOf(found.values());
    }

    private void refilter() {
        int caret = editor.caretOffset();
        if (anchor < 0 || caret < anchor) {
            hide();
            return;
        }
        String prefix = editor.text().substring(anchor, caret).toLowerCase(Locale.ROOT);
        List<Suggestion> filtered = new ArrayList<>();
        for (Suggestion suggestion : all) {
            String word = suggestion.text().toLowerCase(Locale.ROOT);
            if (prefix.isEmpty() || word.startsWith(prefix)) {
                filtered.add(suggestion);
            }
        }
        // What a plugin knows about this kind of file first, then the file's own words.
        filtered.sort(Comparator.comparing((Suggestion s) -> "word".equals(s.kind())).thenComparing(Suggestion::text));
        list.getItems().setAll(filtered.subList(0, Math.min(MAX_ITEMS, filtered.size())));
        if (filtered.isEmpty()) {
            hide();
            return;
        }
        list.getSelectionModel().select(0);
        list.scrollTo(0);
    }

    /** Answers whether the key was for the popup. */
    private boolean handleKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                return true;
            }
            case ENTER, TAB -> {
                apply();
                return true;
            }
            case UP -> {
                move(-1);
                return true;
            }
            case DOWN -> {
                move(1);
                return true;
            }
            case PAGE_UP -> {
                move(-10);
                return true;
            }
            case PAGE_DOWN -> {
                move(10);
                return true;
            }
            case BACK_SPACE -> {
                // Filtered again after the character is gone.
                javafx.application.Platform.runLater(this::refilter);
                return false;
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
        int next = Math.floorMod(list.getSelectionModel().getSelectedIndex() + by, size);
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void apply() {
        Suggestion chosen = list.getSelectionModel().getSelectedItem();
        // Where the word began and ends, taken before hiding: hiding forgets where it began.
        int start = anchor;
        int caret = editor.caretOffset();
        hide();
        if (chosen == null || start < 0 || caret < start) {
            return;
        }
        editor.replace(start, caret, chosen.text());
    }

    private void hide() {
        popup.hide();
        all = List.of();
        anchor = -1;
    }

    private int wordStart(int caret) {
        String text = editor.text();
        int start = caret;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '_')) {
            start--;
        }
        return start;
    }

    private static final class Cell extends ListCell<Suggestion> {
        @Override
        protected void updateItem(Suggestion item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.text());
            org.kordamp.ikonli.javafx.FontIcon icon = com.smide.ui.Icons.of("word".equals(item.kind())
                    ? "fth-type" : "fth-box");
            setGraphic(icon);
        }
    }
}
