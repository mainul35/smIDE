package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.editor.TextEditor;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Applies a server's edits: to the open editor when the file has one (so it undoes as
 * one step and stays unsaved), straight to disk otherwise.
 */
public final class WorkspaceEdits {

    private WorkspaceEdits() {
    }

    /** JavaFX thread. Returns false if any part failed. */
    public static boolean apply(Ide ide, WorkspaceEdit edit) {
        if (edit == null) {
            return true;
        }
        boolean ok = true;
        if (edit.getDocumentChanges() != null) {
            for (Either<TextDocumentEdit, ResourceOperation> change : edit.getDocumentChanges()) {
                if (change.isLeft()) {
                    TextDocumentEdit te = change.getLeft();
                    ok &= applyTextEdits(ide, Positions.path(te.getTextDocument().getUri()), te.getEdits());
                } else {
                    ok &= applyResource(ide, change.getRight());
                }
            }
        } else if (edit.getChanges() != null) {
            for (Map.Entry<String, List<TextEdit>> e : edit.getChanges().entrySet()) {
                ok &= applyTextEdits(ide, Positions.path(e.getKey()), e.getValue());
            }
        }
        return ok;
    }

    public static boolean applyTextEdits(Ide ide, Path file, List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return true;
        }
        List<TextEdit> sorted = new ArrayList<>(edits);
        // Last edit first, so earlier offsets stay valid.
        sorted.sort(Comparator.comparing((TextEdit t) -> t.getRange().getStart().getLine())
                .thenComparing(t -> t.getRange().getStart().getCharacter()).reversed());
        TextEditor editor = ide.editors().find(file).flatMap(e -> e.asText()).orElse(null);
        if (editor != null) {
            apply(editor, sorted);
            return true;
        }
        try {
            String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            String lineSep = text.contains("\r\n") ? "\r\n" : "\n";
            String normalized = text.replace("\r\n", "\n");
            StringBuilder sb = new StringBuilder(normalized);
            for (TextEdit t : sorted) {
                int start = Positions.offset(normalized, t.getRange().getStart());
                int end = Positions.offset(normalized, t.getRange().getEnd());
                sb.replace(start, end, t.getNewText().replace("\r\n", "\n"));
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString().replace("\n", lineSep), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            ide.notifications().error("Cannot apply edit", file + ": " + e.getMessage());
            return false;
        }
    }

    /** Applies edits already sorted last-first to an open editor. */
    public static void apply(TextEditor editor, List<TextEdit> sortedLastFirst) {
        int caret = editor.caretOffset();
        for (TextEdit t : sortedLastFirst) {
            int[] range = Positions.offsets(editor, t.getRange());
            editor.replace(range[0], range[1], t.getNewText().replace("\r\n", "\n"));
        }
        editor.moveCaret(editor.lineOf(Math.min(caret, editor.text().length())),
                editor.columnOf(Math.min(caret, editor.text().length())));
    }

    private static boolean applyResource(Ide ide, ResourceOperation op) {
        try {
            if (op instanceof CreateFile c) {
                Path p = Positions.path(c.getUri());
                Files.createDirectories(p.getParent());
                if (!Files.exists(p)) {
                    Files.createFile(p);
                }
            } else if (op instanceof DeleteFile d) {
                Path p = Positions.path(d.getUri());
                ide.editors().find(p).ifPresent(e -> ide.editors().close(e));
                Files.deleteIfExists(p);
            } else if (op instanceof RenameFile r) {
                Path from = Positions.path(r.getOldUri());
                Path to = Positions.path(r.getNewUri());
                boolean wasOpen = ide.editors().find(from).map(e -> ide.editors().close(e)).orElse(false);
                Files.createDirectories(to.getParent());
                Files.move(from, to);
                if (wasOpen) {
                    ide.editors().open(to);
                }
            }
            return true;
        } catch (IOException e) {
            ide.notifications().error("Cannot apply file operation", e.getMessage());
            return false;
        }
    }
}
