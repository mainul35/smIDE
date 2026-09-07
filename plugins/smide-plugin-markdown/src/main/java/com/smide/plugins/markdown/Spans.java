package com.smide.plugins.markdown;

import com.smide.api.lang.Token;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Turns tokens and decorations into the one {@link StyleSpans} a CodeArea takes, the way
 * the core's editor does: every range boundary becomes a cut, and each piece between cuts
 * carries every class that covers it. The class names are the {@code tok-*} classes the
 * IDE stylesheet already colours, so the raw editor looks like any other.
 */
final class Spans {

    /** A decoration painted over the tokens: a search hit or a diagnostic. */
    record Overlay(int start, int end, String styleClass) {
    }

    private Spans() {
    }

    static StyleSpans<Collection<String>> of(int length, List<Token> tokens, List<Overlay> overlays) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        if (length == 0) {
            builder.add(List.of(), 0);
            return builder.create();
        }
        if (overlays == null || overlays.isEmpty()) {
            int pos = 0;
            for (Token t : tokens) {
                int start = Math.max(t.start(), pos);
                int end = Math.min(t.end(), length);
                if (end <= start) {
                    continue;
                }
                if (start > pos) {
                    builder.add(List.of(), start - pos);
                }
                builder.add(List.of(t.type().styleClass()), end - start);
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
        for (Token t : tokens) {
            cuts.add(clamp(t.start(), length));
            cuts.add(clamp(t.end(), length));
        }
        for (Overlay o : overlays) {
            cuts.add(clamp(o.start(), length));
            cuts.add(clamp(o.end(), length));
        }
        Integer[] points = cuts.toArray(Integer[]::new);
        int ti = 0;
        for (int i = 0; i + 1 < points.length; i++) {
            int from = points[i];
            int to = points[i + 1];
            if (to <= from) {
                continue;
            }
            List<String> classes = new ArrayList<>(2);
            while (ti < tokens.size() && tokens.get(ti).end() <= from) {
                ti++;
            }
            if (ti < tokens.size()) {
                Token t = tokens.get(ti);
                if (t.start() <= from && t.end() >= to) {
                    classes.add(t.type().styleClass());
                }
            }
            for (Overlay o : overlays) {
                if (o.start() <= from && o.end() >= to) {
                    classes.add(o.styleClass());
                }
            }
            builder.add(classes, to - from);
        }
        return builder.create();
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }
}
