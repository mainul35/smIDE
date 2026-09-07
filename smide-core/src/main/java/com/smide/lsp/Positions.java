package com.smide.lsp;

import com.smide.api.editor.TextEditor;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.net.URI;
import java.nio.file.Path;

/** Conversions between LSP positions, editor offsets and file URIs. */
public final class Positions {

    private Positions() {
    }

    public static Position of(TextEditor editor, int offset) {
        return new Position(editor.lineOf(offset), editor.columnOf(offset));
    }

    public static Position caret(TextEditor editor) {
        return new Position(editor.caretLine(), editor.caretColumn());
    }

    public static int offset(TextEditor editor, Position p) {
        return editor.offsetOf(p.getLine(), p.getCharacter());
    }

    public static int[] offsets(TextEditor editor, Range r) {
        return new int[]{offset(editor, r.getStart()), offset(editor, r.getEnd())};
    }

    /** Offsets in a plain string, for files that are not open in an editor. */
    public static int offset(String text, Position p) {
        int line = 0;
        int i = 0;
        while (line < p.getLine() && i < text.length()) {
            int nl = text.indexOf('\n', i);
            if (nl < 0) {
                return text.length();
            }
            i = nl + 1;
            line++;
        }
        return Math.min(text.length(), i + p.getCharacter());
    }

    public static String uri(Path file) {
        return file.toUri().toString();
    }

    public static Path path(String uri) {
        try {
            return Path.of(URI.create(uri)).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            // Some servers percent-encode the drive colon; decode by hand.
            String s = uri.startsWith("file:///") ? uri.substring(8) : uri.replaceFirst("^file:/*", "");
            s = java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
            return Path.of(s).toAbsolutePath().normalize();
        }
    }
}
