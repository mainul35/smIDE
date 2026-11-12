package com.smide.api.lang;

import java.util.regex.Pattern;

/**
 * A stretch of a file that is written in a different language from the file.
 *
 * <p>An HTML page is not only HTML: between {@code <style>} and {@code </style>} it is
 * CSS, and between {@code <script>} and {@code </script>} it is JavaScript. A language
 * declares those stretches once, and the IDE uses the declaration twice - to colour them
 * as what they are, and to hand them to the language server that knows how to check them.
 *
 * @param languageId the id of the language inside, as a language plugin registers it
 * @param around     matches a whole region, with group 1 the opening, group 2 the body
 *                   and group 3 the closing
 */
public record EmbeddedRegion(String languageId, Pattern around) {

    /** A region between an opening tag and a closing one, as HTML and XML spell them. */
    public static EmbeddedRegion between(String languageId, String open, String close) {
        return new EmbeddedRegion(languageId,
                Pattern.compile("(?s)(" + open + ")(.*?)(" + close + "|\\z)"));
    }
}
