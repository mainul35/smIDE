package com.smide.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * How large to draw everything, on the one platform that will not tell us by itself.
 *
 * <p>Windows and macOS hand JavaFX their scaling factor and a window comes out the size
 * of every other window on the screen. Linux does not: the toolkit renders at 1:1 whatever
 * the desktop is set to, so on a 13-inch 1920x1080 laptop running at 150% smIDE appears at
 * two-thirds the size of everything around it, which reads as "the fonts are broken"
 * rather than as a scaling default.
 *
 * <p>So the scale is worked out here and set as {@code glass.gtk.uiScale} before the
 * toolkit starts, which is the only time that property is read. In order of precedence:
 *
 * <ol>
 *   <li>{@code -Dsmide.uiScale=1.5} or {@code SMIDE_UI_SCALE}, for overriding everything</li>
 *   <li>{@code ui.scale} in {@code ~/.smide/settings.json}, which Settings writes</li>
 *   <li>{@code GDK_SCALE} and {@code GDK_DPI_SCALE}, which the desktop sets for GTK apps</li>
 * </ol>
 *
 * <p>Nothing found means nothing set, and JavaFX behaves as it did before.
 */
public final class UiScale {

    /** The settings key, so the Settings page and this agree without a shared constant. */
    public static final String KEY = "ui.scale";

    private UiScale() {
    }

    /**
     * Works out the scale and applies it. Call from {@code main} before the toolkit
     * starts; after that the property is no longer read and this does nothing useful.
     */
    public static void apply() {
        if (!isLinux() || System.getProperty("glass.gtk.uiScale") != null) {
            return;
        }
        String scale = resolve();
        if (scale != null) {
            System.setProperty("glass.gtk.uiScale", scale);
        }
    }

    /** The scale that will be used, as JavaFX wants it, or null to leave it alone. */
    static String resolve() {
        String explicit = firstOf(System.getProperty("smide.uiScale"),
                System.getenv("SMIDE_UI_SCALE"));
        if (explicit != null) {
            return normalise(explicit);
        }
        String saved = fromSettings();
        if (saved != null) {
            return normalise(saved);
        }
        return fromGtk();
    }

    /**
     * {@code ui.scale} out of the settings file, read by hand.
     *
     * <p>Read as text rather than through the settings service, which does not exist yet:
     * this runs before the IDE is built, because the property it sets is read before the
     * toolkit starts. The file is a flat JSON object of strings, so a line is enough.
     */
    private static String fromSettings() {
        Path file = Path.of(System.getProperty("user.home", "."), ".smide", "settings.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String text = line.strip();
                if (!text.startsWith("\"" + KEY + "\"")) {
                    continue;
                }
                int colon = text.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String value = text.substring(colon + 1).strip()
                        .replace("\"", "").replace(",", "").strip();
                return value.isEmpty() || value.equalsIgnoreCase("auto") ? null : value;
            }
        } catch (IOException | RuntimeException e) {
            // An unreadable settings file is not a reason to fail to start.
        }
        return null;
    }

    /**
     * What the desktop told GTK applications.
     *
     * <p>{@code GDK_SCALE} is a whole number - the 2 in a 200% desktop - and
     * {@code GDK_DPI_SCALE} is the fraction alongside it, usually 0.5 when GDK_SCALE is 2
     * and the real intent was 100%. They multiply.
     */
    private static String fromGtk() {
        double scale = number(System.getenv("GDK_SCALE"), 0);
        double dpi = number(System.getenv("GDK_DPI_SCALE"), 0);
        double combined = (scale > 0 ? scale : 1) * (dpi > 0 ? dpi : 1);
        if (scale <= 0 && dpi <= 0) {
            return null;
        }
        return combined > 1.05 ? trim(combined) : null;
    }

    /** JavaFX takes a number or a percentage; a number is the less ambiguous of the two. */
    private static String normalise(String value) {
        String text = value.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.equals("auto")) {
            return null;
        }
        if (text.endsWith("%")) {
            double percent = number(text.substring(0, text.length() - 1), 100);
            return trim(percent / 100);
        }
        double number = number(text, 0);
        return number > 0 ? trim(number) : null;
    }

    private static String trim(double value) {
        // 1.5 rather than 1.5000000000000002, which JavaFX parses but nobody wants in a log.
        return String.valueOf(Math.round(value * 100) / 100.0);
    }

    private static double number(String text, double fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstOf(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return !os.contains("win") && !os.contains("mac") && !os.contains("darwin");
    }
}
