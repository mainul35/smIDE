package com.smide.editor;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which {@code <div>} does this {@code </div>} close? */
class TagsTest {

    private static final IntPredicate NOTHING_SKIPPED = at -> false;

    /** The caret sits where the text has a | in it. */
    private static Optional<Tags.Match> at(String marked) {
        int caret = marked.indexOf('|');
        return Tags.around(marked.replace("|", ""), caret, NOTHING_SKIPPED);
    }

    private static String name(String marked, boolean partner) {
        String text = marked.replace("|", "");
        Tags.Match match = at(marked).orElseThrow();
        return partner
                ? text.substring(match.partnerStart(), match.partnerEnd())
                : text.substring(match.start(), match.end());
    }

    @Test
    void anOpeningTagFindsItsClosingOne() {
        String page = "<di|v class=\"panel\">hi</div>";
        Tags.Match match = at(page).orElseThrow();

        assertTrue(match.found());
        assertEquals("div", name(page, false));
        assertEquals("div", name(page, true));
        assertEquals(1, match.start(), "the name, not the angle bracket");
        assertEquals(23, match.partnerStart(), "the name in </div>");
    }

    @Test
    void aClosingTagFindsItsOpeningOne() {
        Tags.Match match = at("<div><p>x</p></di|v>").orElseThrow();

        assertEquals(1, match.partnerStart(), "the outer div, not the p");
        assertEquals(4, match.partnerEnd());
    }

    @Test
    void nestingIsCountedRatherThanTheFirstOneFound() {
        // The outer div's partner is the last </div>, not the one closing the inner.
        String page = "<di|v><div>a</div><div>b</div></div>";
        Tags.Match match = at(page).orElseThrow();

        assertEquals(page.replace("|", "").lastIndexOf("</div>") + 2, match.partnerStart());
    }

    @Test
    void andTheOtherWayRoundToo() {
        String text = "<div><div>a</div><div>b</div></div>";
        int caret = text.lastIndexOf("</div>") + 3;

        Tags.Match match = Tags.around(text, caret, NOTHING_SKIPPED).orElseThrow();

        assertEquals(1, match.partnerStart(), "the first div, which is the one still open");
    }

    @Test
    void theCaretAnywhereInTheTagWorks() {
        for (String marked : new String[] {
            "|<div>x</div>", "<|div>x</div>", "<div|>x</div>", "<div>|x</div>"}) {
            Optional<Tags.Match> match = at(marked);
            if (marked.equals("<div>|x</div>")) {
                assertTrue(match.isEmpty(), "past the tag is not in the tag: " + marked);
            } else {
                assertTrue(match.orElseThrow().found(), "should have matched: " + marked);
            }
        }
    }

    @Test
    void aTagThatIsNeverClosedIsSaidToHaveNoOtherEnd() {
        Tags.Match match = at("<div><p|>text").orElseThrow();

        assertFalse(match.found());
        assertEquals(-1, match.partnerStart());
    }

    @Test
    void anElementThatClosesItselfHasNoOtherEnd() {
        assertTrue(at("<b|r>").isEmpty(), "br never has a closing tag");
        assertTrue(at("<im|g src=\"x.png\">").isEmpty());
        assertTrue(at("<di|v class=\"x\"/>").isEmpty(), "and neither does a self-closed one");
        assertTrue(at("<!DOCTY|PE html>").isEmpty());
    }

    @Test
    void aSelfClosedTagInBetweenDoesNotCount() {
        String page = "<di|v><br><img src=\"x\"><input/></div>";

        assertTrue(at(page).orElseThrow().found());
        assertEquals("div", name(page, true));
    }

    @Test
    void differentNamesDoNotMatchEachOther() {
        Tags.Match match = at("<di|v><span>x</span></div>").orElseThrow();

        assertEquals("div", name("<di|v><span>x</span></div>", true));
        assertTrue(match.partnerStart() > "<div><span>x</span>".length());
    }

    @Test
    void awayFromATagThereIsNothingToSay() {
        assertTrue(at("hello | world").isEmpty());
        assertTrue(at("a < b and c |> d").isEmpty(), "a less-than sign is not a tag");
        assertTrue(Tags.around("", 0, NOTHING_SKIPPED).isEmpty());
        assertTrue(Tags.around(null, 0, NOTHING_SKIPPED).isEmpty());
    }

    @Test
    void aTagInAStringOrACommentIsNotATag() {
        // <script>const s = "<div>";</script> - the one in the string must not be paired
        // with the real one further down the page.
        String text = "<div>\n  const s = \"<div>\";\n</div>";
        int quoted = text.indexOf('"');
        int quoteEnd = text.lastIndexOf('"');
        IntPredicate inString = at -> at >= quoted && at <= quoteEnd;

        Tags.Match match = Tags.around(text, 2, inString).orElseThrow();

        assertEquals(text.lastIndexOf("</div>") + 2, match.partnerStart(),
                "the real </div>, not confused by the one in the string");
    }

    @Test
    void theNameIsMatchedWholeAndNotAsAPrefix() {
        String page = "<p|><paragraph>x</paragraph></p>";

        assertEquals(page.replace("|", "").lastIndexOf("</p>") + 2,
                at(page).orElseThrow().partnerStart(), "</p>, not </paragraph>");
    }
}
