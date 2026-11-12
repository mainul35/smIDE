package com.smide.editor;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which bracket does the one beside the caret belong to? */
class BracketsTest {

    private static final String PAIRS = "()[]{}";
    private static final IntPredicate NOTHING_SKIPPED = at -> false;

    private static Optional<Brackets.Match> at(String text, int caret) {
        return Brackets.beside(text, caret, PAIRS, NOTHING_SKIPPED);
    }

    /** The caret sits where the text has a | in it. */
    private static Optional<Brackets.Match> at(String marked) {
        int caret = marked.indexOf('|');
        return at(marked.replace("|", ""), caret);
    }

    @Test
    void aCloserFindsItsOpener() {
        // str(userinfo.get(settings)) with the caret just after the last bracket.
        Brackets.Match match = at("f(g(x))|").orElseThrow();

        assertTrue(match.found());
        assertEquals(6, match.at());
        assertEquals(1, match.partner());
    }

    @Test
    void anOpenerFindsItsCloser() {
        Brackets.Match match = at("f|(g(x))").orElseThrow();

        assertEquals(1, match.at());
        assertEquals(6, match.partner());
    }

    @Test
    void nestingIsCountedRatherThanTheFirstOneFound() {
        Brackets.Match match = at("|(a(b)c(d)e)").orElseThrow();

        assertEquals(0, match.at());
        assertEquals(10, match.partner(), "the last one, not the one closing (b)");
    }

    @Test
    void theCharacterBeforeTheCaretIsLookedAtFirst() {
        // Between a closer and an opener: ...)|(... should be about the one just typed.
        Brackets.Match match = at("(a)|(b)").orElseThrow();

        assertEquals(2, match.at(), "the ) behind the caret, not the ( in front of it");
        assertEquals(0, match.partner());
    }

    @Test
    void theKindsOfBracketDoNotMatchEachOther() {
        Brackets.Match match = at("{[(|)]}").orElseThrow();

        assertEquals(2, match.at(), "the ( behind the caret");
        assertEquals(3, match.partner(), "and the ) in front of it, not the ] or the }");

        assertEquals(1, at("{[(x)]|}").orElseThrow().partner(), "] belongs to [");
    }

    @Test
    void aBracketWithNoPartnerIsFoundAndSaidToHaveNone() {
        Brackets.Match match = at("f(g(x)|").orElseThrow();

        assertEquals(5, match.at());
        assertEquals(3, match.partner());

        Brackets.Match lonely = at("f(|").orElseThrow();
        assertFalse(lonely.found(), "there is no closer for either of them");
        assertEquals(-1, lonely.partner());
    }

    @Test
    void awayFromABracketThereIsNothingToSay() {
        assertTrue(at("fo|o(bar)").isEmpty());
        assertTrue(at("|").isEmpty());
        assertTrue(Brackets.beside("", 0, PAIRS, NOTHING_SKIPPED).isEmpty());
        assertTrue(Brackets.beside("(x)", 1, "", NOTHING_SKIPPED).isEmpty(),
                "a language with no brackets has no bracket to match");
    }

    @Test
    void bracketsInsideAStringAreCharactersRatherThanBrackets() {
        // print("(") - the bracket in the string must not be paired with the code's.
        String text = "print(\"(\")";
        int quoted = text.indexOf('"');
        IntPredicate inString = charAt -> charAt >= quoted && charAt <= text.lastIndexOf('"');

        Brackets.Match match = Brackets.beside(text, text.length(), PAIRS, inString).orElseThrow();

        assertEquals(9, match.at(), "the ) that closes print");
        assertEquals(5, match.partner(), "and it belongs to print's own (");
    }

    @Test
    void andTheCaretBesideOneOfThoseSaysNothing() {
        String text = "print(\"(\")";
        int quoted = text.indexOf('"');
        IntPredicate inString = charAt -> charAt >= quoted && charAt <= text.lastIndexOf('"');

        assertTrue(Brackets.beside(text, 8, PAIRS, inString).isEmpty(),
                "a bracket inside a string is not a bracket");
    }

    @Test
    void theCaretAtTheVeryStartOrEndIsSafe() {
        assertEquals(0, at("|(a)").orElseThrow().at());
        assertEquals(2, at("(a)|").orElseThrow().at());
        assertTrue(Brackets.beside("abc", 3, PAIRS, NOTHING_SKIPPED).isEmpty());
        assertTrue(Brackets.beside(null, 0, PAIRS, NOTHING_SKIPPED).isEmpty());
    }
}
