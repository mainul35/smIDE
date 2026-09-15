package com.smide.editor;

import javafx.animation.PauseTransition;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The colour a stylesheet line writes, shown as a swatch beside it in the gutter - so
 * {@code -smide-accent: #0b6e7f;} can be seen for the colour it is without running anything.
 *
 * <p>Style sheets only: CSS, and the SCSS, Sass and Less written the same way. What counts is
 * a hex colour ({@code #rgb}, {@code #rgba}, {@code #rrggbb}, {@code #rrggbbaa}) or an
 * {@code rgb()}, {@code rgba()}, {@code hsl()} or {@code hsla()} with plain arguments, on the
 * value side of a declaration - after its colon - so an id selector such as {@code #add:hover}
 * is not mistaken for one. Whatever JavaFX cannot read as a colour is left alone.
 */
public final class ColorSwatches {

    static final Set<String> EXTENSIONS = Set.of("css", "scss", "sass", "less");

    private static final Pattern COLOR = Pattern.compile(
            "#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\\b|\\b(?:rgba?|hsla?)\\([^()]*\\)");
    private static final Duration AFTER_EDIT = Duration.millis(300);

    private ColorSwatches() {
    }

    /** Shows swatches in a stylesheet's gutter and keeps them in step with its text; other files are left alone. */
    public static void install(CodeEditor editor) {
        String name = String.valueOf(editor.path().getFileName()).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(name.substring(dot + 1))) {
            return;
        }
        PauseTransition later = new PauseTransition(AFTER_EDIT);
        later.setOnFinished(e -> editor.setColorSwatches(find(editor.text())));
        editor.addTextListener(text -> later.playFromStart());
        editor.setColorSwatches(find(editor.text()));
    }

    /** The colours written on each line, as written, by zero-based line. */
    static Map<Integer, List<String>> find(String text) {
        Map<Integer, List<String>> found = new TreeMap<>();
        int line = 0;
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            String content = text.substring(start, end);
            int colon = content.indexOf(':');
            if (colon >= 0) {
                Matcher m = COLOR.matcher(content);
                while (m.find()) {
                    if (m.start() > colon && isColor(m.group())) {
                        found.computeIfAbsent(line, l -> new ArrayList<>()).add(m.group());
                    }
                }
            }
            if (end == text.length()) {
                break;
            }
            start = end + 1;
            line++;
        }
        return found;
    }

    /** The colour a literal names, or null when JavaFX cannot read it as one. */
    static Color colorOf(String literal) {
        try {
            return Color.web(literal);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isColor(String literal) {
        return colorOf(literal) != null;
    }
}
