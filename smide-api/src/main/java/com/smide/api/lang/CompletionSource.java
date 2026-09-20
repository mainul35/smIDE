package com.smide.api.lang;

import java.nio.file.Path;
import java.util.List;

/**
 * Words a plugin offers while someone is typing, for a language with no server of its own -
 * Gradle's build vocabulary in a {@code build.gradle}, which is where most of what is written
 * there comes from.
 *
 * <p>Not a language server: nothing here knows types or scope. It is the list a reader would
 * otherwise keep in their head, offered beside the words already in the file. Called on the
 * UI thread while typing, so it answers from what it already has.
 */
public interface CompletionSource {

    /** Whether this has anything to offer in this file. */
    boolean handles(Path file);

    /**
     * What to offer where the caret is.
     *
     * @param file   the file being edited
     * @param text   its whole text
     * @param offset where the caret is in it
     */
    List<Suggestion> suggest(Path file, String text, int offset);

    /**
     * One offer.
     *
     * @param text   what is written when it is chosen
     * @param detail what it is, in a few words, shown beside it; may be null
     * @param kind   {@code keyword}, {@code function}, {@code word}: only the icon and the order
     */
    record Suggestion(String text, String detail, String kind) {
        public Suggestion(String text, String detail) {
            this(text, detail, "keyword");
        }
    }
}
