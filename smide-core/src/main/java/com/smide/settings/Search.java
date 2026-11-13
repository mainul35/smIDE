package com.smide.settings;

import java.util.Locale;

/**
 * Matching what somebody typed into a search box against what is on a page.
 *
 * <p>Every search box in the Settings dialog works the same way, which is the point of
 * this class existing rather than three slightly different filters: the query is split
 * into words and every word has to appear somewhere in the thing being searched. Word
 * order does not matter and neither does punctuation, so "font size" finds a row labelled
 * "Size" beside a field called "Font", and "n shift" finds the same shortcut as
 * "shift+N". A filter that only matched a single run of characters would miss both.
 *
 * <p>Shortcuts need one more thing. smIDE stores them with the modifier spelled
 * {@code shortcut} - the one that is Ctrl on Windows and Linux and Cmd on macOS - and
 * nobody searching for a key they pressed types that word. So a stored shortcut is
 * searched under every spelling of it, and typing the keys you actually press finds the
 * action bound to them.
 *
 * <p>All of it is text in, boolean out, so all of it is tested without a window.
 */
final class Search {

    private Search() {
    }

    /**
     * Text reduced to lower-case words separated by single spaces.
     *
     * <p>{@code +} and {@code _} become spaces because they are separators in the two
     * things most worth finding - {@code shortcut+shift+N} and {@code editor.fontFamily} -
     * and somebody searching will type a space or nothing at all.
     */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace('+', ' ')
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('.', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** Several fields as one normalised string to search in. */
    static String haystack(String... fields) {
        StringBuilder out = new StringBuilder();
        if (fields != null) {
            for (String field : fields) {
                String part = normalise(field);
                if (!part.isEmpty()) {
                    if (out.length() > 0) {
                        out.append(' ');
                    }
                    out.append(part);
                }
            }
        }
        return out.toString();
    }

    /**
     * Whether every word of the query appears in the haystack.
     *
     * <p>An empty query matches everything: a search box that has not been typed in yet
     * must not hide the list it is sitting above.
     */
    static boolean matches(String query, String haystack) {
        String wanted = normalise(query);
        if (wanted.isEmpty()) {
            return true;
        }
        String within = haystack == null ? "" : haystack;
        for (String word : wanted.split(" ")) {
            if (!within.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Convenience: match a query against several fields at once. */
    static boolean matchesAny(String query, String... fields) {
        return matches(query, haystack(fields));
    }

    /**
     * A stored shortcut under every spelling of its platform modifier.
     *
     * <p>{@code shortcut+shift+N} is searchable as itself, as {@code ctrl shift n} and as
     * {@code cmd shift n}, because what somebody types is the key they pressed and not the
     * word smIDE writes in the settings file.
     */
    static String shortcutTerms(String shortcut) {
        String base = normalise(shortcut);
        if (base.isEmpty() || !base.contains("shortcut")) {
            return base;
        }
        return base
                + " " + base.replace("shortcut", "ctrl")
                + " " + base.replace("shortcut", "cmd")
                + " " + base.replace("shortcut", "command");
    }
}
