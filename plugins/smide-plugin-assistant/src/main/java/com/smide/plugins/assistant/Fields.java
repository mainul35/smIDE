package com.smide.plugins.assistant;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The little format the practice turns come back in: {@code === NAME ===} on a line, then
 * the value until the next one.
 *
 * <p>Not JSON, deliberately. A question and its marking scheme are multi-line Markdown
 * containing code, quotes and backslashes, and a model that has to escape all of that into
 * a JSON string gets it wrong often enough to matter - whereas a line of equals signs is
 * something it cannot mis-escape. Anything before the first field is dropped, which
 * absorbs the "Sure, here is a question:" that some models cannot help adding.
 */
final class Fields {

    private Fields() {
    }

    /** Field names upper-cased, values stripped of surrounding blank lines. */
    static Map<String, String> of(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (text == null) {
            return fields;
        }
        String current = null;
        StringBuilder value = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            String name = nameOf(line);
            if (name != null) {
                if (current != null) {
                    fields.put(current, trim(value.toString()));
                }
                current = name;
                value.setLength(0);
            } else if (current != null) {
                value.append(line).append('\n');
            }
        }
        if (current != null) {
            fields.put(current, trim(value.toString()));
        }
        return fields;
    }

    /** True once the text has any field at all, so a stream can be parsed as it arrives. */
    static boolean has(String text, String field) {
        return of(text).containsKey(field);
    }

    static String get(Map<String, String> fields, String name, String fallback) {
        String value = fields.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * The field name on a delimiter line, or null.
     *
     * <p>Only three equals signs, never a hash. A question's own Markdown headings are
     * hashes, and treating those as delimiters would cut a question in half at the first
     * subheading it contained. Tolerant about the closing signs, which a model does
     * sometimes drop, and about the case.
     */
    private static String nameOf(String line) {
        String text = line.strip();
        if (!text.startsWith("===")) {
            return null;
        }
        String inner = text.replaceAll("^=+", "").replaceAll("=+$", "").strip();
        if (inner.isEmpty() || inner.length() > 20 || !inner.matches("[A-Za-z ]+")) {
            return null; // A row of equals signs underlining a heading.
        }
        return inner.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String trim(String value) {
        return value.strip();
    }
}
