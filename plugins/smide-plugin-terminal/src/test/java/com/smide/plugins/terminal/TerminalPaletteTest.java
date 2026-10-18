package com.smide.plugins.terminal;

import com.jediterm.core.Color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Can what the terminal writes be read?
 *
 * <p>Reported as "yellow on white is barely visible", which it was: JediTerm paints with xterm's
 * palette and xterm's yellow is {@code #cdcd00}, chosen for a black terminal. On smIDE's paper
 * background that is 1.3:1 - a word written in highlighter.
 *
 * <p>Measured as contrast ratios, the same ones accessibility guidance is written in, because
 * "looks all right to me" is how the first palette got here.
 */
class TerminalPaletteTest {

    /** smIDE's light background, and its dark one. */
    private static final int PAPER = 0xf6f8fa;
    private static final int INK = 0x11161c;

    /** The line for ordinary text. */
    private static final double READABLE = 4.5;

    private static final String[] NAMES = {
        "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white",
        "bright black", "bright red", "bright green", "bright yellow", "bright blue",
        "bright magenta", "bright cyan", "bright white",
    };

    private static double luminance(int rgb) {
        double[] parts = {
            ((rgb >> 16) & 0xFF) / 255.0, ((rgb >> 8) & 0xFF) / 255.0, (rgb & 0xFF) / 255.0,
        };
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i] <= 0.03928 ? parts[i] / 12.92
                    : Math.pow((parts[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2];
    }

    private static double contrast(int one, int two) {
        double a = luminance(one) + 0.05;
        double b = luminance(two) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }

    private static int rgbOf(TerminalPalette palette, int index) {
        Color colour = palette.getForeground(
                com.jediterm.terminal.TerminalColor.index(index));
        return (colour.getRed() << 16) | (colour.getGreen() << 8) | colour.getBlue();
    }

    @Test
    void everyColourOnPaperCanBeRead() {
        StringBuilder worst = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            // White on white is impossible and is not promised; everything else is.
            if (i == 7 || i == 15) {
                continue;
            }
            double ratio = contrast(rgbOf(TerminalPalette.LIGHT, i), PAPER);
            if (ratio < READABLE) {
                worst.append(String.format("%s %.1f:1  ", NAMES[i], ratio));
            }
        }
        assertEquals("", worst.toString(), "unreadable on the light theme: " + worst);
    }

    @Test
    void yellowInParticular() {
        double was = contrast(0xcdcd00, PAPER);
        double now = contrast(rgbOf(TerminalPalette.LIGHT, 3), PAPER);

        assertTrue(was < 2, "xterm's yellow on paper was " + String.format("%.1f:1", was));
        assertTrue(now >= READABLE, "and is now " + String.format("%.1f:1", now));
    }

    @Test
    void andWhatIsMeantToBeWhiteIsAtLeastVisible() {
        for (int i : new int[] {7, 15}) {
            double ratio = contrast(rgbOf(TerminalPalette.LIGHT, i), PAPER);
            assertTrue(ratio >= 1.8, NAMES[i] + " on paper is " + String.format("%.1f:1", ratio));
        }
    }

    @Test
    void theDarkThemeKeepsTheColoursItWasDrawnFor() {
        assertEquals(0xcdcd00, rgbOf(TerminalPalette.DARK, 3), "xterm's yellow, on a dark terminal");
        for (int i = 0; i < 16; i++) {
            if (i == 0 || i == 8) {
                continue;
            }
            double ratio = contrast(rgbOf(TerminalPalette.DARK, i), INK);
            assertTrue(ratio >= 3, NAMES[i] + " on ink is " + String.format("%.1f:1", ratio));
        }
    }

    @Test
    void theTwoPalettesAreNotTheSame() {
        assertNotEquals(rgbOf(TerminalPalette.DARK, 3), rgbOf(TerminalPalette.LIGHT, 3));
        assertEquals(TerminalPalette.LIGHT, TerminalPalette.forTheme(false));
        assertEquals(TerminalPalette.DARK, TerminalPalette.forTheme(true));
    }

    @Test
    void aColourMeansTheSameInFrontOfTextAsBehindIt() {
        for (int i = 0; i < 16; i++) {
            assertEquals(rgbOf(TerminalPalette.LIGHT, i),
                    colourBehind(TerminalPalette.LIGHT, i),
                    NAMES[i] + " should be one colour, not two");
        }
    }

    private static int colourBehind(TerminalPalette palette, int index) {
        Color colour = palette.getBackground(com.jediterm.terminal.TerminalColor.index(index));
        return (colour.getRed() << 16) | (colour.getGreen() << 8) | colour.getBlue();
    }
}
