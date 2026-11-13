package com.smide.plugins.notebook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CellContextTest {

    /** Markdown, then three code cells; the kinds and the order are what is being tested. */
    private static Notebook notebook() throws Exception {
        return Notebook.read("""
                {"cells":[
                 {"cell_type":"markdown","metadata":{},"source":["# Title\\n","prose\\n"]},
                 {"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],
                  "source":["import json\\n"]},
                 {"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],
                  "source":["data = {}\\n","other = 1\\n"]},
                 {"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],
                  "source":["json.dumps(data)"]}],
                 "metadata":{},"nbformat":4,"nbformat_minor":5}
                """);
    }

    @Test
    void joinsTheCodeCellsAboveTheOneBeingEdited() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 3, null);

        assertEquals("import json\ndata = {}\nother = 1\njson.dumps(data)\n", context.text());
        assertEquals(4, context.lineCount());
    }

    @Test
    void leavesTheProseOut() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 1, null);

        assertEquals("import json\n", context.text());
        // The markdown cell contributed no lines, so it maps to nothing.
        assertEquals(-1, context.lineOf(0, 0));
    }

    @Test
    void stopsAtTheCellBeingEdited() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 2, null);

        assertEquals("import json\ndata = {}\nother = 1\n", context.text());
    }

    @Test
    void mapsACellsLineToTheJoinedText() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 3, null);

        assertEquals(0, context.lineOf(1, 0));
        assertEquals(1, context.lineOf(2, 0));
        assertEquals(2, context.lineOf(2, 1));
        assertEquals(3, context.lineOf(3, 0));
    }

    @Test
    void mapsAJoinedLineBackToItsCell() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 3, null);

        assertEquals(new CellContext.At(1, 0), context.at(0).orElseThrow());
        assertEquals(new CellContext.At(2, 0), context.at(1).orElseThrow());
        assertEquals(new CellContext.At(2, 1), context.at(2).orElseThrow());
        assertEquals(new CellContext.At(3, 0), context.at(3).orElseThrow());
        assertTrue(context.at(4).isEmpty());
        assertTrue(context.at(-1).isEmpty());
    }

    @Test
    void usesWhatIsBeingTypedRatherThanWhatTheFileHolds() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 3, "json.du");

        assertEquals("import json\ndata = {}\nother = 1\njson.du\n", context.text());
        assertEquals(3, context.lineOf(3, 0));
    }

    @Test
    void aCellWithNoTrailingNewlineStillEndsItsLine() throws Exception {
        Notebook notebook = Notebook.read("""
                {"cells":[
                 {"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],
                  "source":["a = 1"]},
                 {"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],
                  "source":["b = 2"]}],
                 "metadata":{},"nbformat":4,"nbformat_minor":5}
                """);

        CellContext context = CellContext.upTo(notebook.cells(), 1, null);

        assertEquals("a = 1\nb = 2\n", context.text());
        assertEquals(1, context.lineOf(1, 0));
    }

    @Test
    void aProseCellBeingEditedHasNothingInTheContext() throws Exception {
        CellContext context = CellContext.upTo(notebook().cells(), 0, "# Title");

        assertEquals("", context.text());
        assertFalse(context.hasCell());
    }

    @Test
    void aCodeCellBeingEditedIsInItsOwnContext() throws Exception {
        assertTrue(CellContext.upTo(notebook().cells(), 1, null).hasCell());
    }

    // ---------------------------------------------------------------- what is not Python

    @Test
    void aLineMagicBecomesACommentOfTheSameWidth() {
        String python = CellContext.pythonOf("%matplotlib inline\nx = 1\n");

        assertEquals("#matplotlib inline\nx = 1\n", python);
        assertEquals(18, python.lines().findFirst().orElseThrow().length());
    }

    @Test
    void aCellMagicBecomesACommentToo() {
        assertEquals("#%timeit\nsum(range(10))\n",
                CellContext.pythonOf("%%timeit\nsum(range(10))\n"));
    }

    @Test
    void aShellLineBecomesAComment() {
        assertEquals("#pip install pandas\n", CellContext.pythonOf("!pip install pandas\n"));
    }

    @Test
    void anIndentedMagicKeepsItsIndent() {
        assertEquals("    #time run()\n", CellContext.pythonOf("    %time run()\n"));
    }

    @Test
    void aHelpQueryBecomesAComment() {
        assertEquals("#en?\n", CellContext.pythonOf("len?\n"));
    }

    @Test
    void realPythonIsLeftAloneEvenWhereItUsesTheSameCharacters() {
        for (String code : new String[]{"x = 5 % 2\n", "ok = a != b\n", "q = x if y else z\n",
                "s = f\"{a!r}\"\n", "print('100%')\n", "x = a or not b\n"}) {
            assertEquals(code, CellContext.pythonOf(code), code);
        }
    }

    @Test
    void aMagicInTheContextDoesNotShiftTheLinesBelowIt() throws Exception {
        Notebook notebook = Notebook.read("""
                {"cells":[
                 {"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],
                  "source":["%matplotlib inline\\n","import json\\n"]},
                 {"cell_type":"code","execution_count":null,"metadata":{},"outputs":[],
                  "source":["json.dumps({})"]}],
                 "metadata":{},"nbformat":4,"nbformat_minor":5}
                """);

        CellContext context = CellContext.upTo(notebook.cells(), 1, null);

        assertEquals("#matplotlib inline\nimport json\njson.dumps({})\n", context.text());
        assertEquals(2, context.lineOf(1, 0));
        assertEquals(new CellContext.At(0, 1), context.at(1).orElseThrow());
    }

    // ---------------------------------------------------------------- tracebacks

    @Test
    void terminalColoursAreTakenOutOfWhatIsDrawn() {
        assertEquals("ZeroDivisionError: division by zero",
                Ansi.strip("\u001B[31mZeroDivisionError\u001B[39m: division by zero"));
        assertEquals("Cell In[2], line 1", Ansi.strip(
                "\u001B[36mCell\u001B[39m\u001B[36m \u001B[39m\u001B[32mIn[2]\u001B[39m"
                        + "\u001B[32m, line 1\u001B[39m"));
        assertEquals("plain", Ansi.strip("plain"));
        assertEquals("", Ansi.strip(null));
    }
}
