package com.smide.plugins.notebook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CellKindTest {

    @Test
    void onlyACodeCellIsChecked() {
        // What is drawn under a word hangs off this: a cell that is not checked gets no
        // squiggles, because nothing will ever come back to say its problems have gone.
        assertTrue(CellKind.CODE.isChecked());
        assertFalse(CellKind.MARKDOWN.isChecked());
        assertFalse(CellKind.RAW.isChecked());
    }

    @Test
    void whatNbformatCallsThem() {
        assertEquals("code", CellKind.CODE.jsonName());
        assertEquals("markdown", CellKind.MARKDOWN.jsonName());
        assertEquals("raw", CellKind.RAW.jsonName());
    }

    @Test
    void aKindNothingHereKnowsIsProse() {
        assertEquals(CellKind.MARKDOWN, CellKind.parse("heading"));
        assertEquals(CellKind.MARKDOWN, CellKind.parse("html"));
        assertEquals(CellKind.RAW, CellKind.parse("something-else"));
        assertEquals(CellKind.RAW, CellKind.parse(null));
        assertEquals(CellKind.CODE, CellKind.parse("CODE"));
    }

    @Test
    void aKindNothingHereKnowsIsNotChecked() {
        // The reason the two are tested together: an unknown kind falling through to RAW
        // must not end up checked as Python.
        assertFalse(CellKind.parse("html").isChecked());
        assertFalse(CellKind.parse("something-else").isChecked());
    }
}
