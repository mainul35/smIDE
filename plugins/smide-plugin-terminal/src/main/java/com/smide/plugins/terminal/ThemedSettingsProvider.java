package com.smide.plugins.terminal;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.smide.api.Ide;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;

/**
 * JediTerm settings that follow the IDE: foreground, background and selection come from
 * the theme tokens {@code text}, {@code paper} and {@code selection}; the font is Cascadia
 * Mono or Consolas (or the JVM's monospaced font) at the size in {@code terminal.fontSize}.
 *
 * <p>Colours are cached and refreshed by {@link #refresh()} on a theme change; JediTerm
 * asks for them on every paint, so the cache keeps the hot path cheap. One instance is
 * shared by every terminal session.
 */
final class ThemedSettingsProvider extends DefaultSettingsProvider {

    /** Settings key for the font size in points. */
    static final String FONT_SIZE_KEY = "terminal.fontSize";
    static final int DEFAULT_FONT_SIZE = 13;

    private static final List<String> PREFERRED_FONTS = List.of("Cascadia Mono", "Cascadia Code", "Consolas");

    private final Ide ide;
    private final String fontFamily;
    private volatile TerminalColor foreground;
    private volatile TerminalColor background;
    private volatile TextStyle selection;
    private volatile float fontSize;

    ThemedSettingsProvider(Ide ide) {
        this.ide = ide;
        this.fontFamily = pickFontFamily();
        refresh();
    }

    /** Re-reads the theme colours and font size. Any thread; the values are plain snapshots. */
    void refresh() {
        foreground = color("text", 0x16, 0x20, 0x2b);
        background = color("paper", 0xf6, 0xf8, 0xfa);
        TerminalColor selectionBackground = color("selection", 0xd5, 0xe5, 0xf2);
        selection = new TextStyle(foreground, selectionBackground);
        fontSize = Math.max(6, ide.settings().getInt(FONT_SIZE_KEY, DEFAULT_FONT_SIZE));
    }

    /** The current default text style, for pushing into an already-running terminal. */
    TextStyle currentDefaultStyle() {
        return new TextStyle(foreground, background);
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return foreground;
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return background;
    }

    @Override
    public TextStyle getDefaultStyle() {
        return currentDefaultStyle();
    }

    @Override
    public TextStyle getSelectionColor() {
        return selection;
    }

    /** Use the theme's selection colour rather than swapping foreground and background. */
    @Override
    public boolean useInverseSelectionColor() {
        return false;
    }

    @Override
    public Font getTerminalFont() {
        return new Font(fontFamily, Font.PLAIN, Math.round(fontSize));
    }

    @Override
    public float getTerminalFontSize() {
        return fontSize;
    }

    @Override
    public boolean useAntialiasing() {
        return true;
    }

    @Override
    public boolean audibleBell() {
        return false;
    }

    @Override
    public int getBufferMaxLinesCount() {
        return 10_000;
    }

    /** A theme token as an AWT colour, for the Swing parts of the widget. */
    java.awt.Color awt(String token, int r, int g, int b) {
        try {
            javafx.scene.paint.Color c = javafx.scene.paint.Color.web(ide.theme().color(token));
            return new java.awt.Color(
                    (int) Math.round(c.getRed() * 255),
                    (int) Math.round(c.getGreen() * 255),
                    (int) Math.round(c.getBlue() * 255));
        } catch (RuntimeException e) {
            return new java.awt.Color(r, g, b);
        }
    }

    /** Parses a theme token such as {@code #16202b} into a terminal colour, with a fallback. */
    private TerminalColor color(String token, int r, int g, int b) {
        try {
            javafx.scene.paint.Color c = javafx.scene.paint.Color.web(ide.theme().color(token));
            return TerminalColor.rgb(
                    (int) Math.round(c.getRed() * 255),
                    (int) Math.round(c.getGreen() * 255),
                    (int) Math.round(c.getBlue() * 255));
        } catch (RuntimeException e) {
            return TerminalColor.rgb(r, g, b);
        }
    }

    /** The first preferred family the JVM knows about, else the logical monospaced font. */
    private static String pickFontFamily() {
        try {
            String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            List<String> installed = Arrays.asList(families);
            for (String preferred : PREFERRED_FONTS) {
                if (installed.contains(preferred)) {
                    return preferred;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // Headless or a broken font configuration: fall through.
        }
        return Font.MONOSPACED;
    }
}
