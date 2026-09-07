package com.smide.api.editor;

import com.smide.api.lang.LanguageSupport;
import com.smide.api.problems.Diagnostic;

import java.util.List;
import java.util.function.Consumer;

/**
 * A code editor over one file: the text, the caret, the selection and the decorations
 * that language intelligence paints on it. Offsets are characters from the start of the
 * text; lines and columns are zero-based.
 */
public interface TextEditor extends Editor {

    String text();

    void setText(String text);

    LanguageSupport language();

    int caretOffset();

    /** Zero-based line of the caret. */
    int caretLine();

    /** Zero-based column of the caret within its line. */
    int caretColumn();

    void moveCaret(int line, int column);

    /** Moves the caret and scrolls so it is visible, optionally selecting a range. */
    void select(int startOffset, int endOffset);

    String selectedText();

    int selectionStart();

    int selectionEnd();

    void replace(int startOffset, int endOffset, String replacement);

    void insert(int offset, String text);

    int offsetOf(int line, int column);

    int lineOf(int offset);

    int columnOf(int offset);

    int lineCount();

    /** Diagnostics to underline; replaces the previous set from the same source. */
    void setDiagnostics(List<Diagnostic> diagnostics);

    /**
     * Shows a column of per-line text in the gutter, or clears it when null.
     *
     * <p>Blame is the reason this exists, but nothing here knows that: an editor draws
     * whatever it is handed.
     */
    default void setLineAnnotations(LineAnnotations annotations) {
    }

    /** The annotations currently shown, or null. */
    default LineAnnotations lineAnnotations() {
        return null;
    }

    /** Called after every edit, with the whole text. */
    void addTextListener(Consumer<String> listener);

    void removeTextListener(Consumer<String> listener);

    /** Called when the caret moves. */
    void addCaretListener(Runnable listener);

    /** The word under the caret, by identifier characters. */
    String wordAtCaret();

    void undo();

    void redo();
}
