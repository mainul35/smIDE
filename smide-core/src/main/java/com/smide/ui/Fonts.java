package com.smide.ui;

import javafx.scene.text.Font;

import java.io.InputStream;
import java.util.List;

/**
 * The fonts smIDE draws code with, carried in the jar rather than hoped for on the
 * machine.
 *
 * <p>The editor asked for Consolas and fell back to whatever the platform offered. On
 * Windows that is Consolas; on Linux it is nothing of the sort, and an IDE whose code
 * font depends on which distribution you installed is one that looks broken on most of
 * them. JetBrains Mono is bundled - four faces, OFL 1.1, about a megabyte - and loaded
 * before the first window is drawn.
 *
 * <p>Loading is best-effort. If the resources are missing or the toolkit refuses them,
 * {@link #monospace()} falls back to asking what is installed, and the CSS keeps its
 * chain of families after the bundled one for the same reason.
 */
public final class Fonts {

    /** The bundled family, when it loaded. */
    public static final String BUNDLED = "JetBrains Mono";

    /** Tried in order when the bundled font is unavailable, across the three platforms. */
    private static final List<String> FALLBACKS = List.of(
            "JetBrains Mono", "Cascadia Mono", "Consolas", "Menlo", "SF Mono",
            "DejaVu Sans Mono", "Liberation Mono", "Noto Sans Mono", "Ubuntu Mono",
            "Courier New", "Monospaced");

    private static final String[] FACES = {
            "JetBrainsMono-Regular.ttf",
            "JetBrainsMono-Bold.ttf",
            "JetBrainsMono-Italic.ttf",
            "JetBrainsMono-BoldItalic.ttf",
    };

    private static boolean loaded;
    private static String monospace;

    private Fonts() {
    }

    /**
     * Registers the bundled faces with the toolkit. Call once, on the JavaFX thread,
     * before any window is built.
     *
     * <p>Every face has to be loaded separately: JavaFX takes bold and italic from the
     * files it was given, and with only the regular one it synthesises them by slanting
     * and smearing, which on a code font is worse than not having them.
     */
    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (String face : FACES) {
            try (InputStream in = Fonts.class.getResourceAsStream("/fonts/" + face)) {
                if (in == null) {
                    System.err.println("smIDE: bundled font missing: " + face);
                    continue;
                }
                // -1 keeps the file's own size; the size asked for at use time is what counts.
                if (Font.loadFont(in, -1) == null) {
                    System.err.println("smIDE: could not load " + face);
                }
            } catch (Exception e) {
                System.err.println("smIDE: could not load " + face + " - " + e);
            }
        }
    }

    /**
     * The family to draw code in: the bundled one if it loaded, else the first of the
     * fallbacks this machine actually has.
     */
    public static String monospace() {
        if (monospace != null) {
            return monospace;
        }
        List<String> installed = Font.getFamilies();
        for (String family : FALLBACKS) {
            if (installed.contains(family)) {
                monospace = family;
                return monospace;
            }
        }
        // Monospaced is one of JavaFX's own logical families and is always resolvable.
        monospace = "Monospaced";
        return monospace;
    }

    /** The family list for a CSS {@code -fx-font-family}, quoted and comma separated. */
    public static String cssStack() {
        StringBuilder stack = new StringBuilder();
        for (String family : FALLBACKS) {
            stack.append('"').append(family).append("\", ");
        }
        return stack.append("monospace").toString();
    }
}
