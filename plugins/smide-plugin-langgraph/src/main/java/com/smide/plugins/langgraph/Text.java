package com.smide.plugins.langgraph;

/** Where an offset in a file is, as a line and a column. */
final class Text {

    private Text() {
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
