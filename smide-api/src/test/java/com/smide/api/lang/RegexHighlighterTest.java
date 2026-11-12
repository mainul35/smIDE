package com.smide.api.lang;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Does a rule get the colour it asked for?
 *
 * <p>Reported as "syntax highlighting doesn't work well", which was putting it mildly.
 * The rules are compiled into one alternation, and the rule a match belonged to was being
 * read off as the n'th bracket in it - which is only the n'th rule if no rule has brackets
 * of its own. Thirty-seven of the hundred-odd rules in the IDE do, so Markdown painted its
 * headings as comments, JSON painted its values as numbers, HTML painted its tags as
 * entities, and the rules that ran off the end of the numbering were not painted at all.
 */
class RegexHighlighterTest {

    /** The type covering the first occurrence of a piece of text, or null. */
    private static TokenType typeOf(Highlighter highlighter, String source, String text) {
        int at = source.indexOf(text);
        assertEquals(true, at >= 0, "the sample does not contain " + text);
        for (Token token : highlighter.tokenize(source)) {
            if (token.start() <= at && token.end() >= at + text.length()) {
                return token.type();
            }
        }
        return null;
    }

    @Test
    void aRuleWithBracketsOfItsOwnDoesNotStealTheNextRulesColour() {
        // The first rule brings a bracket with it, the way a "to the end, or the end of
        // the file" rule always does. Every rule after it used to be read off by one.
        Highlighter highlighter = new RegexHighlighter()
                .rule("(?s)/\\*.*?(\\*/|\\z)", TokenType.COMMENT)
                .rule("\"[^\"]*\"", TokenType.STRING)
                .rule("\\b\\d+\\b", TokenType.NUMBER)
                .rule("\\bdef\\b", TokenType.KEYWORD);

        String source = "/* note */ def x = \"hi\" + 42;";

        assertEquals(TokenType.COMMENT, typeOf(highlighter, source, "/* note */"));
        assertEquals(TokenType.KEYWORD, typeOf(highlighter, source, "def"));
        assertEquals(TokenType.STRING, typeOf(highlighter, source, "\"hi\""));
        assertEquals(TokenType.NUMBER, typeOf(highlighter, source, "42"));
    }

    @Test
    void andTheLastRulesAreStillPainted() {
        // Reading off by one meant the last rules asked for a bracket that was not there,
        // so they were silently uncoloured however well their pattern matched.
        Highlighter highlighter = new RegexHighlighter()
                .rule("(a)(b)(c)", TokenType.KEYWORD)
                .rule("x", TokenType.STRING)
                .rule("y", TokenType.NUMBER);

        String source = "abc x y";

        assertEquals(TokenType.KEYWORD, typeOf(highlighter, source, "abc"));
        assertNotNull(typeOf(highlighter, source, "x"), "x was left uncoloured");
        assertEquals(TokenType.STRING, typeOf(highlighter, source, "x"));
        assertEquals(TokenType.NUMBER, typeOf(highlighter, source, "y"));
    }

    @Test
    void theFirstRuleToMatchStillWins() {
        Highlighter highlighter = new RegexHighlighter()
                .rule("(key)word", TokenType.KEYWORD)
                .rule("keyword", TokenType.STRING);

        assertEquals(TokenType.KEYWORD, typeOf(highlighter, "keyword", "keyword"));
    }

    @Test
    void tokensComeOutInOrderAndDoNotOverlap() {
        Highlighter highlighter = new RegexHighlighter()
                .rule("(?s)/\\*.*?(\\*/|\\z)", TokenType.COMMENT)
                .rule("\"[^\"]*\"", TokenType.STRING)
                .rule("\\b\\d+\\b", TokenType.NUMBER);

        List<Token> tokens = highlighter.tokenize("1 /* two */ \"three\" 4 /* five");

        int previousEnd = 0;
        for (Token token : tokens) {
            assertEquals(true, token.start() >= previousEnd,
                    "token at " + token.start() + " overlaps the one ending at " + previousEnd);
            previousEnd = token.end();
        }
        assertEquals(5, tokens.size(), "every one of them should have been found");
    }
}
