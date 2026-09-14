package com.smide.api.execution;

import java.util.function.Supplier;

/**
 * A line a run can start from - a main method, a script's {@code __main__} block - which
 * the editor marks with a run icon in its gutter.
 *
 * @param line          zero-based, like every line in the API
 * @param name          what the icon's menu calls it. A saved or detected configuration of
 *                      the same type and name is run in its place, so settings the reader
 *                      gave it - arguments, environment - still apply.
 * @param configuration makes the configuration when the icon is used, and only then
 */
public record RunMarker(int line, String name, Supplier<RunConfiguration> configuration) {

    /** The zero-based line an offset into a text is on. */
    public static int lineOf(CharSequence text, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
