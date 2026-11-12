package com.smide.api.lang;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A language written inside another one is coloured as itself. */
class EmbeddedHighlighterTest {

    private static final Highlighter MARKUP = new RegexHighlighter()
            .rule("</?[A-Za-z][\\w-]*", TokenType.TAG)
            .rule("/?>", TokenType.TAG);

    private static final Highlighter INSIDE = new RegexHighlighter()
            .rule("//[^\\n]*", TokenType.COMMENT)
            .rule("\\bconst\\b", TokenType.KEYWORD)
            .rule("\\b\\d+\\b", TokenType.NUMBER);

    private static final Highlighter HIGHLIGHTER = new EmbeddedHighlighter(MARKUP, List.of(
            EmbeddedHighlighter.Island.between("<script\\b[^>]*>", "</script\\s*>", () -> INSIDE)));

    private static TokenType typeOf(String source, String text) {
        int at = source.indexOf(text);
        assertEquals(true, at >= 0, "the sample does not contain " + text);
        for (Token token : HIGHLIGHTER.tokenize(source)) {
            if (token.start() <= at && token.end() >= at + text.length()) {
                return token.type();
            }
        }
        return null;
    }

    @Test
    void theBodyIsColouredByTheLanguageItIsWrittenIn() {
        String source = "<p>hi</p>\n<script>\n// a note\nconst n = 42;\n</script>\n<p>bye</p>";

        assertEquals(TokenType.COMMENT, typeOf(source, "// a note"));
        assertEquals(TokenType.KEYWORD, typeOf(source, "const"));
        assertEquals(TokenType.NUMBER, typeOf(source, "42"));
    }

    @Test
    void andTheTagsAroundItStayTheHostLanguages() {
        String source = "<script>\nconst n = 42;\n</script>";

        assertEquals(TokenType.TAG, typeOf(source, "<script"));
        assertEquals(TokenType.TAG, typeOf(source, "</script"));
    }

    @Test
    void whatIsOutsideAnIslandIsStillTheHostLanguages() {
        String source = "<div>const</div>\n<script>const</script>";

        // The same word, two languages: markup outside, a keyword in.
        assertEquals(TokenType.TAG, typeOf(source, "<div"));
        assertEquals(null, typeOf(source, ">const</div"), "the host has no rule for it");
        assertEquals(TokenType.KEYWORD, typeOf("<script>const</script>", "const"));
    }

    @Test
    void anUnclosedIslandRunsToTheEnd() {
        String source = "<script>\nconst n = 42;";

        assertEquals(TokenType.KEYWORD, typeOf(source, "const"));
        assertEquals(TokenType.NUMBER, typeOf(source, "42"));
    }

    @Test
    void tokensComeOutInOrderAcrossTheJoins() {
        List<Token> tokens = HIGHLIGHTER.tokenize(
                "<p>a</p><script>const n = 1;</script><p>b</p><script>const m = 2;</script>");

        int previousEnd = 0;
        for (Token token : tokens) {
            assertEquals(true, token.start() >= previousEnd,
                    "token at " + token.start() + " overlaps the one ending at " + previousEnd);
            previousEnd = token.end();
        }
    }

    @Test
    void anIslandThatIsNotThreeGroupsIsRefusedWhenItIsBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new EmbeddedHighlighter(MARKUP,
                List.of(new EmbeddedHighlighter.Island(
                        java.util.regex.Pattern.compile("(<script>)(.*?)"), () -> INSIDE))));
    }
}
