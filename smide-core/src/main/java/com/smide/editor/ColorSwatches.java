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

    /**
     * A colour where it is written: the literal as it stands, and where in the document it
     * stands, so exactly those characters can be replaced when another colour is chosen.
     */
    record Literal(int line, int start, int end, String text) {
    }

    /** The colours written on each line, as written, by zero-based line. */
    static Map<Integer, List<Literal>> find(String text) {
        Map<Integer, List<Literal>> found = new TreeMap<>();
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
                        found.computeIfAbsent(line, l -> new ArrayList<>())
                                .add(new Literal(line, start + m.start(), start + m.end(), m.group()));
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

    /**
     * A colour written the way the one it replaces was written.
     *
     * <p>A stylesheet is somebody's to keep, and a picker that turned every {@code #fff}
     * into {@code rgba(255, 255, 255, 1)} would be rewriting the file rather than changing
     * a colour in it. Hex stays hex, in the same case and in three digits where the colour
     * can still be said in three; {@code rgb()} stays {@code rgb()}, {@code hsl()} stays
     * {@code hsl()}; and transparency is kept wherever the line already carried it.
     */
    static String format(String original, Color color) {
        String text = original.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        boolean carried = lower.startsWith("rgba") || lower.startsWith("hsla")
                || text.length() == 5 || text.length() == 9;
        boolean alpha = carried || color.getOpacity() < 1;
        if (lower.startsWith("rgb")) {
            String parts = channel(color.getRed()) + ", " + channel(color.getGreen())
                    + ", " + channel(color.getBlue());
            return alpha ? "rgba(" + parts + ", " + opacity(color) + ")" : "rgb(" + parts + ")";
        }
        if (lower.startsWith("hsl")) {
            return hsl(color, alpha);
        }
        String hex = hex(color, alpha, text.length() <= 5 && sayableInThree(color, alpha));
        // Upper case only where the line was already upper case: #C0392B stays shouting.
        return text.chars().anyMatch(c -> c >= 'A' && c <= 'F') ? hex.toUpperCase(Locale.ROOT) : hex;
    }

    /** Whether every channel is a repeated digit, which is all #rgb can say. */
    private static boolean sayableInThree(Color color, boolean alpha) {
        return channel(color.getRed()) % 17 == 0 && channel(color.getGreen()) % 17 == 0
                && channel(color.getBlue()) % 17 == 0 && (!alpha || channel(color.getOpacity()) % 17 == 0);
    }

    private static String hex(Color color, boolean alpha, boolean threeDigits) {
        StringBuilder out = new StringBuilder("#");
        int[] channels = alpha
                ? new int[] {channel(color.getRed()), channel(color.getGreen()),
                        channel(color.getBlue()), channel(color.getOpacity())}
                : new int[] {channel(color.getRed()), channel(color.getGreen()), channel(color.getBlue())};
        for (int value : channels) {
            if (threeDigits) {
                out.append(Integer.toHexString(value / 17));
            } else {
                out.append(value < 16 ? "0" : "").append(Integer.toHexString(value));
            }
        }
        return out.toString();
    }

    /** The colour as hue, saturation and lightness, which is what an hsl() line is written in. */
    private static String hsl(Color color, boolean alpha) {
        double red = color.getRed();
        double green = color.getGreen();
        double blue = color.getBlue();
        double max = Math.max(red, Math.max(green, blue));
        double min = Math.min(red, Math.min(green, blue));
        double lightness = (max + min) / 2;
        double spread = max - min;
        double hue = 0;
        double saturation = 0;
        if (spread > 0) {
            saturation = lightness > 0.5 ? spread / (2 - max - min) : spread / (max + min);
            if (max == red) {
                hue = (green - blue) / spread + (green < blue ? 6 : 0);
            } else if (max == green) {
                hue = (blue - red) / spread + 2;
            } else {
                hue = (red - green) / spread + 4;
            }
            hue *= 60;
        }
        String parts = Math.round(hue) + ", " + Math.round(saturation * 100) + "%, "
                + Math.round(lightness * 100) + "%";
        return alpha ? "hsla(" + parts + ", " + opacity(color) + ")" : "hsl(" + parts + ")";
    }

    private static int channel(double value) {
        return (int) Math.round(value * 255);
    }

    /** Transparency with no more digits than it needs: 1, 0.5, 0.22. */
    private static String opacity(Color color) {
        String text = String.format(Locale.ROOT, "%.2f", color.getOpacity());
        while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
