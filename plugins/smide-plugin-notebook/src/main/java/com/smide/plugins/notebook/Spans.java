package com.smide.plugins.notebook;

import com.smide.api.lang.Token;
import com.smide.api.problems.Diagnostic;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Tokens and underlines as the one {@link StyleSpans} a {@code CodeArea} takes.
 *
 * <p>Every range boundary becomes a cut, and each piece between cuts carries every class
 * that covers it, so a diagnostic can be drawn over a keyword without either losing its
 * colour. The class names are the {@code tok-*} and {@code diag-*} classes the IDE's
 * own stylesheet already defines, which is what makes a cell look like the editor next to
 * it rather than like a plugin's idea of an editor.
 */
final class Spans {

    /** A decoration painted over the tokens. */
    record Overlay(int start, int end, String styleClass) {
    }

    private Spans() {
    }

    /** The overlays a cell's diagnostics come to, given where each line starts. */
    static List<Overlay> overlays(List<Diagnostic> diagnostics, String text) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        List<Integer> lineStarts = new ArrayList<>();
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineStarts.add(i + 1);
            }
        }
        List<Overlay> out = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            int start = offsetOf(lineStarts, text.length(), diagnostic.startLine(), diagnostic.startColumn());
            int end = offsetOf(lineStarts, text.length(), diagnostic.endLine(), diagnostic.endColumn());
            if (end <= start) {
                // A zero-width problem still has to be visible, so it takes one character.
                end = Math.min(start + 1, text.length());
            }
            if (end <= start) {
                continue;
            }
            out.add(new Overlay(start, end, "diag-"
                    + diagnostic.severity().name().toLowerCase(java.util.Locale.ROOT)));
        }
        return List.copyOf(out);
    }

    private static int offsetOf(List<Integer> lineStarts, int length, int line, int column) {
        if (line < 0 || line >= lineStarts.size()) {
            return length;
        }
        return Math.max(0, Math.min(lineStarts.get(line) + Math.max(0, column), length));
    }

    static StyleSpans<Collection<String>> of(int length, List<Token> tokens, List<Overlay> overlays) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        if (length == 0) {
            builder.add(List.of(), 0);
            return builder.create();
        }
        if (overlays == null || overlays.isEmpty()) {
            int pos = 0;
            for (Token token : tokens) {
                int start = Math.max(token.start(), pos);
                int end = Math.min(token.end(), length);
                if (end <= start) {
                    continue;
                }
                if (start > pos) {
                    builder.add(List.of(), start - pos);
                }
                builder.add(List.of(token.type().styleClass()), end - start);
                pos = end;
            }
            if (pos < length) {
                builder.add(List.of(), length - pos);
            }
            return builder.create();
        }

        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        cuts.add(length);
        for (Token token : tokens) {
            cuts.add(clamp(token.start(), length));
            cuts.add(clamp(token.end(), length));
        }
        for (Overlay overlay : overlays) {
            cuts.add(clamp(overlay.start(), length));
            cuts.add(clamp(overlay.end(), length));
        }
        Integer[] points = cuts.toArray(Integer[]::new);
        int next = 0;
        for (int i = 0; i + 1 < points.length; i++) {
            int from = points[i];
            int to = points[i + 1];
            if (to <= from) {
                continue;
            }
            List<String> classes = new ArrayList<>(2);
            while (next < tokens.size() && tokens.get(next).end() <= from) {
                next++;
            }
            if (next < tokens.size()) {
                Token token = tokens.get(next);
                if (token.start() <= from && token.end() >= to) {
                    classes.add(token.type().styleClass());
                }
            }
            for (Overlay overlay : overlays) {
                if (overlay.start() <= from && overlay.end() >= to) {
                    classes.add(overlay.styleClass());
                }
            }
            builder.add(classes, to - from);
        }
        return builder.create();
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}
