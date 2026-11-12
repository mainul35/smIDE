package com.smide.lsp;

import com.smide.api.lang.EmbeddedRegion;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The copy of a page that is only its CSS, or only its JavaScript.
 *
 * <p>What matters is that it is the same shape as the page: the same number of lines, of
 * the same lengths, with that language's part of it in the place it was. Then a server's
 * complaint about line 5, column 12 needs no translating on the way back.
 */
class VirtualTextTest {

    private static final Pattern STYLE =
            EmbeddedRegion.between("css", "<style\\b[^>]*>", "</style\\s*>").around();
    private static final Pattern SCRIPT =
            EmbeddedRegion.between("javascript", "<script\\b[^>]*>", "</script\\s*>").around();

    private static final String PAGE = String.join("\n",
            "<!DOCTYPE html>",
            "<style>",
            "  .panel { colour: red; }",
            "</style>",
            "<div>hi</div>",
            "<script>",
            "  const n = 1;",
            "</script>",
            "");

    @Test
    void theCopyIsTheSameShapeAsThePage() {
        String css = VirtualText.of(PAGE, STYLE);

        assertEquals(PAGE.length(), css.length(), "the same number of characters");
        assertEquals(PAGE.lines().count(), css.lines().count(), "the same number of lines");
        String[] was = PAGE.split("\n", -1);
        String[] now = css.split("\n", -1);
        for (int i = 0; i < was.length; i++) {
            assertEquals(was[i].length(), now[i].length(), "line " + (i + 1) + " is as long");
        }
    }

    @Test
    void onlyThatLanguageIsLeftInIt() {
        String[] lines = VirtualText.of(PAGE, STYLE).split("\n", -1);

        assertEquals("  .panel { colour: red; }", lines[2]);
        for (int i = 0; i < lines.length; i++) {
            if (i != 2) {
                assertTrue(lines[i].isBlank(), "line " + (i + 1) + " should be blank: " + lines[i]);
            }
        }
    }

    @Test
    void andTheTagsThemselvesAreBlankedToo() {
        // <style> is not CSS: a server given it reports an error on the first line of
        // every page that has one.
        String css = VirtualText.of(PAGE, STYLE);

        assertTrue(css.lines().noneMatch(l -> l.contains("<style")), "the opening tag is gone");
        assertTrue(css.lines().noneMatch(l -> l.contains("</style")), "and the closing one");
    }

    @Test
    void theScriptIsTakenByItsOwnPattern() {
        String[] lines = VirtualText.of(PAGE, SCRIPT).split("\n", -1);

        assertEquals("  const n = 1;", lines[6]);
        assertTrue(lines[2].isBlank(), "the CSS is not JavaScript");
    }

    @Test
    void aPageWithNoneOfThatLanguageHasNoCopy() {
        assertNull(VirtualText.of("<div>only markup</div>", STYLE));
        assertNull(VirtualText.of("", STYLE));
        assertNull(VirtualText.of(null, STYLE));
        assertNull(VirtualText.of("<style></style>", STYLE), "an empty block is nothing to check");
    }

    @Test
    void everyBlockOfThatLanguageIsInTheOneCopy() {
        String page = "<style>a{}</style>\n<p>x</p>\n<style>b{}</style>\n";

        String[] lines = VirtualText.of(page, STYLE).split("\n", -1);

        assertEquals("       a{}        ", lines[0]);
        assertEquals("       b{}        ", lines[2]);
    }

    @Test
    void anUnclosedBlockRunsToTheEndOfThePage() {
        String page = "<style>\n  .a { color: red }\n";

        String[] lines = VirtualText.of(page, STYLE).split("\n", -1);

        assertEquals("  .a { color: red }", lines[1]);
    }

    @Test
    void windowsLineEndingsAreLeftAlone() {
        String page = "<style>\r\n  .a{}\r\n</style>\r\n";

        String copy = VirtualText.of(page, STYLE);

        assertEquals(page.length(), copy.length());
        assertEquals(page.chars().filter(c -> c == '\r').count(),
                copy.chars().filter(c -> c == '\r').count(), "the carriage returns are kept");
    }

    @Test
    void aStandInIsAnsweredAboutTheFileItStandsFor() {
        java.nio.file.Path page = java.nio.file.Path.of("C:/x/page.html");
        java.nio.file.Path standIn = java.nio.file.Path.of("C:/x/page.html.css");

        assertEquals(standIn, VirtualPaths.realOf(standIn), "unknown until it is registered");
        VirtualPaths.register(standIn, page);
        assertEquals(page, VirtualPaths.realOf(standIn));
        assertEquals(page, VirtualPaths.realOf(page), "a real file is still itself");
        VirtualPaths.forget(standIn);
        assertEquals(standIn, VirtualPaths.realOf(standIn), "and gone when the file closes");
    }
}
