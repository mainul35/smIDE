package com.smide.api.editor;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where a place in a file leads, for files whose meaning a language server does not know.
 *
 * <p>An XML language server knows that {@code <artifactId>} is an element; it does not know
 * that the text inside names a Maven artifact whose own pom is somewhere on disk. A plugin
 * that does know answers here, and Go to Declaration - Ctrl+click, or the keyboard - asks
 * every provider before it asks the language server.
 */
public interface DeclarationProvider {

    /**
     * What is declared at this offset of this file, or empty to leave it to the language server.
     *
     * @param file   the file being read
     * @param text   its text as it stands in the editor, which may differ from what is on disk
     * @param offset where the caret or the click is
     */
    Optional<Declaration> declarationAt(Path file, String text, int offset);

    /**
     * The characters of the name at this offset, when that name leads somewhere - for
     * Ctrl+hover to draw as a link before anybody clicks, the way IntelliJ says "this can be
     * followed". Empty for anything that cannot: a name that leads nowhere is not a link.
     */
    default Optional<Span> linkAt(Path file, String text, int offset) {
        return Optional.empty();
    }

    /** A run of characters: from {@code start}, up to but not including {@code end}. */
    record Span(int start, int end) {
    }

    /**
     * Where a name leads - or, when this provider knows what the name is but it leads
     * nowhere, why. The second matters: without it the language server would be asked
     * instead, find nothing, and the reader would learn nothing either.
     *
     * @param file        the file to open, or null when there is nowhere to go
     * @param line        zero-based
     * @param column      zero-based
     * @param unavailable why there is nowhere to go; null when there is
     */
    record Declaration(Path file, int line, int column, String unavailable) {

        public static Declaration at(Path file, int line, int column) {
            return new Declaration(file, line, column, null);
        }

        public static Declaration nowhere(String why) {
            return new Declaration(null, 0, 0, why);
        }
    }
}
