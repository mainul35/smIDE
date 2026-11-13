package com.smide.plugins.notebook;

import java.util.regex.Pattern;

/**
 * Takes the terminal colours out of a traceback.
 *
 * <p>IPython formats its tracebacks for a terminal, so what arrives in an {@code error}
 * output is full of {@code ESC[31m} and friends - and a notebook saved by Jupyter keeps
 * them, because they are part of what the kernel said. Drawn as-is in a text node they
 * appear as {@code [31m} litter through the one piece of output somebody reading an error
 * most needs to read.
 *
 * <p>Only the drawing strips them. What goes into the file is what the kernel sent,
 * escapes and all, so a notebook saved here still shows its colours in Jupyter.
 */
final class Ansi {

    /**
     * A CSI escape: {@code ESC [} then parameters then a letter.
     *
     * <p>Also the shorter two-character escapes, which IPython does not emit but a
     * subprocess whose output a cell captured very well might.
     */
    private static final Pattern ESCAPE = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[@-Z\\\\-_]");

    private Ansi() {
    }

    static String strip(String text) {
        if (text == null || text.indexOf('\u001B') < 0) {
            return text == null ? "" : text;
        }
        return ESCAPE.matcher(text).replaceAll("");
    }
}
