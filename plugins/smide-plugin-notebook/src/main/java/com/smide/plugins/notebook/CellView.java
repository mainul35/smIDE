package com.smide.plugins.notebook;

import com.smide.api.lang.Highlighter;
import com.smide.api.lang.Token;
import com.smide.api.problems.Diagnostic;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.fxmisc.richtext.CodeArea;

import java.util.List;
import java.util.function.Consumer;

/**
 * One cell on screen: a header saying what it is, an editor, and its output.
 *
 * <p>The editor is a RichTextFX {@code CodeArea}, the same component the IDE's own editor
 * is built on, so a code cell has the project's Python colouring, its font, its
 * indentation behaviour and its undo history rather than a text box's. That was the point
 * of the exercise: a notebook cell that is not a real editor is where notebook editing in
 * an IDE usually falls down.
 *
 * <p>Each cell grows to fit what is written in it and never scrolls on its own. A cell
 * with its own scrollbar inside a notebook that also scrolls is unusable - the wheel does
 * one or the other depending on where the pointer happens to be - so the height follows
 * the text and the one scrollbar belongs to the notebook.
 *
 * <p>This class draws and reports; it changes nothing. Every edit is handed up to
 * {@link NotebookEditor} as a callback, because the model and the order of the cells are
 * the editor's to change and a cell that reached into the notebook itself would be a
 * second place where the file could be got wrong.
 */
final class CellView extends BorderPane {

    /** What a cell asks the editor to do. */
    interface Listener {
        void run(CellView cell);

        void changed(CellView cell);

        void kindChanged(CellView cell, CellKind kind);

        void focused(CellView cell);

        void addBelow(CellView cell);

        void delete(CellView cell);

        void moveUp(CellView cell);

        void moveDown(CellView cell);

        void splitAtCaret(CellView cell);

        void mergeWithNext(CellView cell);
    }

    private final Cell cell;
    private final Listener listener;
    private final Highlighter highlighter;
    private final CodeArea area = new CodeArea();
    private final OutputView outputs;
    private final Label count = new Label();
    private final ChoiceBox<CellKind> kind = new ChoiceBox<>();
    private final Button run = new Button("▶");

    private List<Diagnostic> diagnostics = List.of();
    private boolean busy;
    /** True while the view is being filled from the model, so it does not report back. */
    private boolean loading;

    CellView(Cell cell, Highlighter highlighter, String fontFamily, int fontSize,
             Listener listener) {
        this.cell = cell;
        this.listener = listener;
        this.highlighter = highlighter;
        this.outputs = new OutputView(fontFamily, fontSize);

        getStyleClass().addAll("notebook-cell", "code-editor");
        setPadding(new Insets(2, 6, 2, 6));

        area.getStyleClass().add("code-area");
        area.setStyle("-fx-font-family: \"" + fontFamily.replace("\"", "")
                + "\", \"Cascadia Mono\", Consolas, monospace; -fx-font-size: " + fontSize + "px;");
        // Wrapped, and not only because the IDE's editor is. A cell that does not wrap is
        // as wide as its longest line, and a column of such cells is wider than the
        // notebook's viewport - which pushes the per-cell buttons off the right-hand edge
        // and puts a horizontal scrollbar under a thing that already scrolls vertically.
        area.setWrapText(true);
        area.setLineHighlighterOn(false);
        // The cell is as tall as its text. Without this a CodeArea in a VBox gets whatever
        // height the layout feels like and shows two lines of a twenty-line cell.
        area.totalHeightEstimateProperty().addListener((property, was, now) -> {
            if (now != null && now > 0) {
                area.setPrefHeight(now + 4);
            }
        });
        area.setMinHeight(22);
        // A CodeArea scrolls itself, and takes the wheel even when, as here, it is exactly
        // as tall as its text and has nowhere to scroll to. Left alone, that means the
        // notebook does not scroll while the pointer is over a cell - which is almost
        // always - and the only way down the page is the scrollbar. The event is handed to
        // the cell instead, from where it reaches the notebook's scroll pane.
        area.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            event.consume();
            if (getParent() != null) {
                getParent().fireEvent(event.copyFor(getParent(), getParent()));
            }
        });
        area.textProperty().addListener((property, was, now) -> {
            if (loading) {
                return;
            }
            highlight();
            listener.changed(this);
        });
        area.focusedProperty().addListener((property, was, now) -> {
            if (now) {
                listener.focused(this);
            }
        });

        setTop(header());
        setCenter(area);
        setBottom(outputs);
        load();
    }

    private HBox header() {
        run.getStyleClass().addAll("icon-button", "notebook-run");
        run.setTooltip(new Tooltip("Run this cell (Ctrl+Enter)"));
        run.setOnAction(event -> listener.run(this));

        count.getStyleClass().add("notebook-count");
        count.setMinWidth(46);
        count.setTooltip(new Tooltip("The kernel's execution count for this cell"));

        kind.getItems().setAll(CellKind.CODE, CellKind.MARKDOWN, CellKind.RAW);
        kind.setConverter(new StringConverter<>() {
            @Override
            public String toString(CellKind value) {
                return value == null ? "" : value.displayName();
            }

            @Override
            public CellKind fromString(String string) {
                return CellKind.parse(string);
            }
        });
        kind.getSelectionModel().selectedItemProperty().addListener((property, was, now) -> {
            if (!loading && now != null && now != cell.kind()) {
                listener.kindChanged(this, now);
            }
        });

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = new HBox(4, run, count, kind, gap,
                small("+", "Add a cell below", () -> listener.addBelow(this)),
                small("↑", "Move up", () -> listener.moveUp(this)),
                small("↓", "Move down", () -> listener.moveDown(this)),
                small("✂", "Split at the caret (Ctrl+Shift+Minus)",
                        () -> listener.splitAtCaret(this)),
                small("⇣", "Merge with the cell below", () -> listener.mergeWithNext(this)),
                small("✕", "Delete this cell", () -> listener.delete(this)));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("notebook-cell-header");
        bar.setPadding(new Insets(2, 2, 2, 2));
        return bar;
    }

    private Button small(String text, String tip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(event -> action.run());
        return button;
    }

    /** Fills the view from the cell, without reporting the change back as an edit. */
    void load() {
        loading = true;
        try {
            if (!area.getText().equals(cell.source())) {
                area.replaceText(cell.source());
                area.getUndoManager().forgetHistory();
            }
            kind.getSelectionModel().select(cell.kind());
            boolean code = cell.kind() == CellKind.CODE;
            run.setVisible(code);
            run.setManaged(code);
            count.setVisible(code);
            count.setManaged(code);
            outputs.show(cell.outputs());
            showCount();
            highlight();
        } finally {
            loading = false;
        }
    }

    Cell cell() {
        return cell;
    }

    CodeArea area() {
        return area;
    }

    String text() {
        return area.getText();
    }

    /**
     * Replaces what is on screen, as an edit rather than a reload.
     *
     * <p>Deliberately not {@link #load}: formatting a cell is something the reader did, so
     * it must mark the notebook modified and be undoable with one Ctrl+Z, exactly like
     * typing. The caret is put back as near as it was, since the text around it has usually
     * only shifted by a space or two.
     */
    void setText(String text) {
        if (area.getText().equals(text)) {
            return;
        }
        int caret = area.getCaretPosition();
        area.replaceText(text);
        area.moveTo(Math.min(caret, area.getLength()));
    }

    /** Writes what is on screen into the model. */
    void flush() {
        cell.setSource(area.getText());
    }

    /** Draws the cell as waiting for the kernel, or not. */
    void setBusy(boolean busy) {
        this.busy = busy;
        run.setDisable(busy);
        showCount();
        if (busy) {
            getStyleClass().add("notebook-cell-busy");
        } else {
            getStyleClass().remove("notebook-cell-busy");
        }
    }

    /** Redraws the output from the model, after a run has added to it. */
    void refreshOutputs() {
        outputs.show(cell.outputs());
        showCount();
    }

    private SyntaxCheck.Problem syntaxProblem;

    void setDiagnostics(List<Diagnostic> diagnostics) {
        this.diagnostics = diagnostics == null ? List.of() : diagnostics;
        highlight();
    }

    /**
     * A syntax error the interpreter itself reported, shown alongside pyright's diagnostics.
     *
     * <p>Kept apart from {@link #setDiagnostics} rather than merged into it, because the two
     * arrive from different places at different times: pyright republishes the whole
     * notebook's diagnostics at once, and would wipe this out every time it did.
     */
    void setSyntaxProblem(SyntaxCheck.Problem problem) {
        this.syntaxProblem = problem;
        highlight();
    }

    /**
     * The interpreter's own complaint as a diagnostic, so one list can be drawn.
     *
     * <p>Python counts lines and columns from one and smIDE counts from zero, which is the
     * sort of difference that puts a mark one character to the left of the problem forever.
     * The end is one character past the start: a syntax error is a point, and a point with
     * no width draws nothing.
     */
    List<Diagnostic> allProblems() {
        if (syntaxProblem == null) {
            return diagnostics;
        }
        List<Diagnostic> all = new java.util.ArrayList<>(diagnostics);
        int line = Math.max(0, syntaxProblem.line() - 1);
        int column = Math.max(0, syntaxProblem.column() - 1);
        all.add(0, new Diagnostic(null, line, column, line, column + 1,
                Diagnostic.Severity.ERROR, syntaxProblem.message(), "python", null));
        return all;
    }

    /** The problems on this cell, for the tooltip and for the editor to collect. */
    List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    void setSelected(boolean selected) {
        if (selected) {
            getStyleClass().add("notebook-cell-selected");
        } else {
            getStyleClass().remove("notebook-cell-selected");
        }
    }

    /**
     * {@code In [3]}, or {@code In [*]} while it runs.
     *
     * <p>The star is Jupyter's and worth keeping: it is the one place a reader looks to see
     * whether a long cell is still going, and which cell the kernel is actually on.
     */
    private void showCount() {
        if (busy) {
            count.setText("[*]");
            return;
        }
        count.setText(cell.executionCount().map(number -> "[" + number + "]").orElse("[ ]"));
    }

    private void highlight() {
        String text = area.getText();
        List<Token> tokens = cell.kind() == CellKind.CODE && highlighter != null
                ? highlighter.tokenize(text) : List.of();
        try {
            area.setStyleSpans(0, Spans.of(text.length(), tokens,
                    Spans.overlays(allProblems(), text)));
        } catch (RuntimeException e) {
            // A span set that does not add up to the text's length throws rather than
            // drawing wrong; the text is simply left uncoloured until the next edit.
        }
    }
}
