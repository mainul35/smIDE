package com.smide.plugins.terminal;

import com.jediterm.core.Color;
import com.jediterm.terminal.emulator.ColorPalette;

/**
 * The sixteen colours a terminal paints with, chosen for the paper they are painted on.
 *
 * <p>JediTerm's own palette is xterm's, and xterm's is for a black background: yellow in it is
 * {@code #cdcd00} and bright yellow is {@code #ffff00}, which on smIDE's light background -
 * {@code #f6f8fa}, very nearly white - is a word written in highlighter. Git writes its commit
 * hashes in yellow, npm writes its warnings in it, and every one of them was a smudge.
 *
 * <p>So there are two palettes. The dark one is xterm's, which is what it was drawn for. The
 * light one is the same hues taken down until each of them has at least the contrast that
 * accessibility guidance asks of ordinary text against that background - which for yellow means
 * something closer to amber, because a yellow that is readable on white is not yellow any more.
 * They are still recognisably red, green, yellow, blue: what matters in a terminal is which of
 * the sixteen this is, and being able to read it.
 */
final class TerminalPalette extends ColorPalette {

    /** xterm's, unchanged: these were chosen for a dark terminal and they work on one. */
    private static final int[] ON_DARK = {
        0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x1e90ff, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
        0x4c4c4c, 0xff0000, 0x00ff00, 0xffff00, 0x4682b4, 0xff00ff, 0x00ffff, 0xffffff,
    };

    /**
     * The same sixteen, dark enough to read on paper.
     *
     * <p>Each is at least 4.5:1 against {@code #f6f8fa}, which is the line accessibility guidance
     * draws for text this size. White and bright white are the exception and have to be: a
     * terminal that is told to write white on white has been told to do something impossible, and
     * the best that can be done is to make it grey enough to see.
     */
    private static final int[] ON_PAPER = {
        0x1c2128, 0xb81e1e, 0x1a7f37, 0x8a6100, 0x0b5cad, 0x9333a8, 0x0f7490, 0x57606a,
        0x57606a, 0xd42c2c, 0x18794e, 0xa06500, 0x1266c7, 0xa248b7, 0x187992, 0x24292f,
    };

    static final TerminalPalette DARK = new TerminalPalette(ON_DARK);
    static final TerminalPalette LIGHT = new TerminalPalette(ON_PAPER);

    private final Color[] colours;

    private TerminalPalette(int[] rgb) {
        colours = new Color[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            colours[i] = new Color(rgb[i]);
        }
    }

    /** The one for the theme in use. */
    static TerminalPalette forTheme(boolean dark) {
        return dark ? DARK : LIGHT;
    }

    @Override
    protected Color getForegroundByColorIndex(int index) {
        return colours[index];
    }

    /**
     * The same colours behind text as in front of it.
     *
     * <p>A background is read as a block of colour rather than through letters, so it does not
     * need the contrast a letter does - and using the brighter original here would leave the two
     * disagreeing about which colour number three is.
     */
    @Override
    protected Color getBackgroundByColorIndex(int index) {
        return colours[index];
    }
}
