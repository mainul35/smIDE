package com.smide.plugins.langgraph;

/** Where an offset in a file is, as a line and a column. */
final class Offsets {

    private Offsets() {
    }

    /**
     * Where a function is defined in a Python text, or -1.
     *
     * <p>Here rather than on the window that uses it: it is text work, and a window is an
     * awkward place to keep anything that has to be used, or tested, without one.
     */
    static int definition(String text, String function) {
        if (function == null || function.isBlank()) {
            return -1;
        }
        java.util.regex.Matcher found = java.util.regex.Pattern
                .compile("(?m)^\\s*(?:async\\s+)?def\\s+(" + java.util.regex.Pattern.quote(function) + ")\\s*\\(")
                .matcher(text);
        return found.find() ? found.start(1) : -1;
    }

    /** The zero-based line and column of an offset. */
    static int[] lineColumn(String text, int offset) {
        int at = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, at - lineStart};
    }
}
