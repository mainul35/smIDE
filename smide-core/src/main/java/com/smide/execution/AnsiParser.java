package com.smide.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits process output into runs of text with the style classes their ANSI SGR
 * sequences ask for. Colours and bold are honoured; cursor movement and the rest are
 * dropped, since the console is a log rather than a terminal.
 */
public final class AnsiParser {

    public record Run(String text, List<String> styles) {
    }

    private static final String[] COLOURS = {
            "ansi-black", "ansi-red", "ansi-green", "ansi-yellow", "ansi-blue", "ansi-magenta", "ansi-cyan", "ansi-white"};

    private String colour;
    private boolean bold;
    private final StringBuilder pendingEscape = new StringBuilder();

    public List<Run> feed(String chunk) {
        List<Run> runs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        String input = pendingEscape.length() > 0 ? pendingEscape + chunk : chunk;
        pendingEscape.setLength(0);
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (c == 0x1b) {
                int end = escapeEnd(input, i);
                if (end < 0) {
                    pendingEscape.append(input, i, n);
                    break;
                }
                flush(runs, text);
                applyEscape(input.substring(i, end + 1));
                i = end + 1;
                continue;
            }
            if (c == '\r') {
                // A bare carriage return is progress output rewriting its line; keep it
                // readable as a line break rather than losing what came before.
                if (i + 1 < n && input.charAt(i + 1) == '\n') {
                    i++;
                    continue;
                }
                text.append('\n');
                i++;
                continue;
            }
            text.append(c);
            i++;
        }
        flush(runs, text);
        return runs;
    }

    private static int escapeEnd(String s, int from) {
        if (from + 1 >= s.length()) {
            return -1;
        }
        char next = s.charAt(from + 1);
        if (next == '[') {
            for (int i = from + 2; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= 0x40 && c <= 0x7e) {
                    return i;
                }
                if (i - from > 64) {
                    return i;
                }
            }
            return -1;
        }
        if (next == ']') {
            // OSC: ends with BEL or ST.
            for (int i = from + 2; i < s.length(); i++) {
                if (s.charAt(i) == 0x07) {
                    return i;
                }
                if (s.charAt(i) == 0x1b && i + 1 < s.length() && s.charAt(i + 1) == '\\') {
                    return i + 1;
                }
            }
            return -1;
        }
        return from + 1;
    }

    private void applyEscape(String seq) {
        if (!seq.startsWith("[") || !seq.endsWith("m")) {
            return;
        }
        String body = seq.substring(2, seq.length() - 1);
        if (body.isEmpty()) {
            colour = null;
            bold = false;
            return;
        }
        for (String part : body.split(";")) {
            int code;
            try {
                code = Integer.parseInt(part.strip());
            } catch (NumberFormatException e) {
                continue;
            }
            if (code == 0) {
                colour = null;
                bold = false;
            } else if (code == 1) {
                bold = true;
            } else if (code == 22) {
                bold = false;
            } else if (code >= 30 && code <= 37) {
                colour = COLOURS[code - 30];
            } else if (code >= 90 && code <= 97) {
                colour = COLOURS[code - 90];
            } else if (code == 39) {
                colour = null;
            }
        }
    }

    private void flush(List<Run> runs, StringBuilder text) {
        if (text.length() == 0) {
            return;
        }
        List<String> styles = new ArrayList<>(2);
        if (colour != null) {
            styles.add(colour);
        }
        if (bold) {
            styles.add("ansi-bold");
        }
        runs.add(new Run(text.toString(), styles));
        text.setLength(0);
    }
}
