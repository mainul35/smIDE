package com.smide.plugins.notebook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotebookTest {

    /** A notebook as Jupyter writes one: source as lines, a null execution count, metadata. */
    private static final String NOTEBOOK = """
            {
             "cells": [
              {
               "cell_type": "markdown",
               "id": "aaaa1111",
               "metadata": {},
               "source": [
                "# Title\\n",
                "\\n",
                "Some prose."
               ]
              },
              {
               "cell_type": "code",
               "execution_count": 3,
               "id": "bbbb2222",
               "metadata": {
                "tags": [
                 "slow"
                ]
               },
               "outputs": [
                {
                 "name": "stdout",
                 "output_type": "stream",
                 "text": [
                  "hello\\n"
                 ]
                }
               ],
               "source": [
                "print('hello')"
               ]
              }
             ],
             "metadata": {
              "kernelspec": {
               "display_name": "Python 3",
               "language": "python",
               "name": "python3"
              },
              "language_info": {
               "name": "python",
               "version": "3.11.9"
              },
              "vscode": {
               "interpreter": {
                "hash": "deadbeef"
               }
              }
             },
             "nbformat": 4,
             "nbformat_minor": 5
            }
            """;

    @Test
    void readsCellsAndTheirContents() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        assertEquals(2, notebook.cellCount());
        assertEquals(CellKind.MARKDOWN, notebook.cell(0).kind());
        assertEquals("# Title\n\nSome prose.", notebook.cell(0).source());
        assertEquals(CellKind.CODE, notebook.cell(1).kind());
        assertEquals("print('hello')", notebook.cell(1).source());
        assertEquals(3, notebook.cell(1).executionCount().orElseThrow());
        assertEquals("bbbb2222", notebook.cell(1).id().orElseThrow());
        assertEquals(4, notebook.nbformat());
        assertEquals(5, notebook.nbformatMinor());
        assertEquals("python", notebook.language().orElseThrow());
        assertTrue(notebook.isPython());
    }

    @Test
    void readsOutputs() throws Exception {
        List<Output> outputs = Notebook.read(NOTEBOOK).cell(1).outputs();

        assertEquals(1, outputs.size());
        assertEquals(Output.STREAM, outputs.get(0).outputType());
        assertEquals("stdout", outputs.get(0).streamName());
        assertEquals("hello\n", outputs.get(0).text());
        assertFalse(outputs.get(0).isStandardError());
    }

    /**
     * The point of the whole model: read a notebook, write it, get the same bytes.
     *
     * <p>Not "equivalent JSON" - the same text, because anything else is a diff somebody
     * has to look at.
     */
    @Test
    void roundTripsByteForByte() throws Exception {
        assertEquals(NOTEBOOK, Notebook.read(NOTEBOOK).toJson());
    }

    @Test
    void keepsFieldsItDoesNotUnderstand() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);
        notebook.cell(1).setSource("print('goodbye')");

        JsonObject written = JsonParser.parseString(notebook.toJson()).getAsJsonObject();

        // The notebook's own unknown metadata.
        assertEquals("deadbeef", written.getAsJsonObject("metadata").getAsJsonObject("vscode")
                .getAsJsonObject("interpreter").get("hash").getAsString());
        // And a cell's.
        assertEquals("slow", written.getAsJsonArray("cells").get(1).getAsJsonObject()
                .getAsJsonObject("metadata").getAsJsonArray("tags").get(0).getAsString());
    }

    @Test
    void writesSourceBackAsLinesKeepingTheirNewlines() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);
        notebook.cell(1).setSource("a = 1\nb = 2\n");

        JsonObject written = JsonParser.parseString(notebook.toJson()).getAsJsonObject();
        var lines = written.getAsJsonArray("cells").get(1).getAsJsonObject().getAsJsonArray("source");

        assertEquals(2, lines.size());
        assertEquals("a = 1\n", lines.get(0).getAsString());
        assertEquals("b = 2\n", lines.get(1).getAsString());
    }

    @Test
    void keepsSourceAsOneStringWhenThatIsHowItArrived() throws Exception {
        Notebook notebook = Notebook.read("""
                {"cells":[{"cell_type":"code","metadata":{},"outputs":[],"execution_count":null,
                "source":"x = 1"}],"metadata":{},"nbformat":4,"nbformat_minor":4}
                """);
        notebook.cell(0).setSource("x = 2");

        JsonObject written = JsonParser.parseString(notebook.toJson()).getAsJsonObject();

        assertEquals("x = 2", written.getAsJsonArray("cells").get(0).getAsJsonObject()
                .get("source").getAsString());
    }

    @Test
    void doesNotEscapeHtmlInSource() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);
        notebook.cell(0).setSource("<b>bold</b> & more");

        assertTrue(notebook.toJson().contains("<b>bold</b> & more"), notebook.toJson());
    }

    // ---------------------------------------------------------------- edits

    @Test
    void insertsACellWithAnIdOfItsOwn() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        Cell added = notebook.insert(1, CellKind.CODE, "y = 2");

        assertEquals(3, notebook.cellCount());
        assertEquals(1, notebook.indexOf(added));
        assertEquals("y = 2", notebook.cell(1).source());
        assertTrue(added.id().isPresent());
        assertFalse(added.id().orElseThrow().equals("bbbb2222"));
        // And the file says so.
        JsonObject written = JsonParser.parseString(notebook.toJson()).getAsJsonObject();
        assertEquals(3, written.getAsJsonArray("cells").size());
        assertEquals("y = 2", Output.multiline(written.getAsJsonArray("cells").get(1)
                .getAsJsonObject().get("source")));
    }

    @Test
    void doesNotGiveACellAnIdInANotebookTooOldForThem() throws Exception {
        Notebook notebook = Notebook.read("""
                {"cells":[],"metadata":{},"nbformat":4,"nbformat_minor":4}
                """);

        assertTrue(notebook.insert(0, CellKind.CODE, "x = 1").id().isEmpty());
    }

    @Test
    void deletesACell() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        notebook.delete(0);

        assertEquals(1, notebook.cellCount());
        assertEquals(CellKind.CODE, notebook.cell(0).kind());
        assertEquals(1, JsonParser.parseString(notebook.toJson()).getAsJsonObject()
                .getAsJsonArray("cells").size());
    }

    @Test
    void movesACellToWhereItShouldEndUp() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        notebook.move(0, 1);

        assertEquals(CellKind.CODE, notebook.cell(0).kind());
        assertEquals(CellKind.MARKDOWN, notebook.cell(1).kind());
        assertEquals("code", JsonParser.parseString(notebook.toJson()).getAsJsonObject()
                .getAsJsonArray("cells").get(0).getAsJsonObject().get("cell_type").getAsString());
    }

    @Test
    void movingPastTheEndStopsAtTheEnd() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        notebook.move(0, 99);

        assertEquals(CellKind.MARKDOWN, notebook.cell(1).kind());
    }

    @Test
    void splitsACellAtAnOffsetAndDropsWhatRanBefore() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);
        notebook.cell(1).setSource("a = 1\nb = 2\n");

        Cell lower = notebook.split(1, 6);

        assertEquals(3, notebook.cellCount());
        assertEquals("a = 1\n", notebook.cell(1).source());
        assertEquals("b = 2\n", lower.source());
        assertEquals(CellKind.CODE, lower.kind());
        assertTrue(notebook.cell(1).outputs().isEmpty());
        assertTrue(notebook.cell(1).executionCount().isEmpty());
        assertTrue(lower.outputs().isEmpty());
        assertTrue(lower.executionCount().isEmpty());
        // The split keeps the cell's metadata, but the halves are not the same cell.
        assertFalse(lower.id().orElseThrow().equals(notebook.cell(1).id().orElseThrow()));
    }

    @Test
    void mergesACellWithTheOneBelowIt() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);
        notebook.cell(1).setSource("a = 1");
        notebook.insert(2, CellKind.CODE, "b = 2");

        Cell merged = notebook.mergeWithNext(1);

        assertEquals(2, notebook.cellCount());
        assertEquals("a = 1\nb = 2", merged.source());
    }

    @Test
    void mergingDoesNotAddASecondNewline() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);
        notebook.cell(1).setSource("a = 1\n");
        notebook.insert(2, CellKind.CODE, "b = 2");

        assertEquals("a = 1\nb = 2", notebook.mergeWithNext(1).source());
    }

    @Test
    void mergingTheLastCellChangesNothing() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        notebook.mergeWithNext(1);

        assertEquals(2, notebook.cellCount());
    }

    @Test
    void changingACodeCellToMarkdownTakesItsOutputFieldsAway() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        notebook.cell(1).setKind(CellKind.MARKDOWN);

        JsonObject cell = JsonParser.parseString(notebook.toJson()).getAsJsonObject()
                .getAsJsonArray("cells").get(1).getAsJsonObject();
        assertEquals("markdown", cell.get("cell_type").getAsString());
        assertFalse(cell.has("outputs"));
        assertFalse(cell.has("execution_count"));
    }

    @Test
    void changingAMarkdownCellToCodeGivesItTheFieldsTheSchemaWants() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        notebook.cell(0).setKind(CellKind.CODE);

        JsonObject cell = JsonParser.parseString(notebook.toJson()).getAsJsonObject()
                .getAsJsonArray("cells").get(0).getAsJsonObject();
        assertEquals("code", cell.get("cell_type").getAsString());
        assertTrue(cell.has("outputs"));
        assertTrue(cell.get("execution_count").isJsonNull());
    }

    @Test
    void amarkdownCellRefusesOutputs() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        notebook.cell(0).addOutput(Output.stream("stdout", "nope"));

        assertTrue(notebook.cell(0).outputs().isEmpty());
        assertFalse(notebook.toJson().contains("nope"));
    }

    @Test
    void clearsEveryOutputAndCount() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);

        notebook.clearAllOutputs();

        assertTrue(notebook.cell(1).outputs().isEmpty());
        assertTrue(notebook.cell(1).executionCount().isEmpty());
    }

    @Test
    void recordsWhatAKernelSentBack() throws Exception {
        Notebook notebook = Notebook.read(NOTEBOOK);
        Cell cell = notebook.cell(1);
        cell.clearOutputs();

        cell.addOutput(Output.stream("stdout", "one\ntwo\n"));
        cell.addOutput(Output.error("ValueError", "bad", List.of("Traceback", "ValueError: bad")));
        cell.setExecutionCount(7);

        List<Output> outputs = cell.outputs();
        assertEquals(2, outputs.size());
        assertEquals("one\ntwo\n", outputs.get(0).text());
        assertEquals("ValueError", outputs.get(1).errorName());
        assertEquals("Traceback\nValueError: bad", outputs.get(1).text());
        assertEquals(7, cell.executionCount().orElseThrow());
        // And it survives a round trip.
        Notebook again = Notebook.read(notebook.toJson());
        assertEquals(2, again.cell(1).outputs().size());
        assertEquals(7, again.cell(1).executionCount().orElseThrow());
    }

    @Test
    void readsAPngOutOfAnOutputEvenWhenItIsWrappedAcrossLines() throws Exception {
        Notebook notebook = Notebook.read("""
                {"cells":[{"cell_type":"code","execution_count":1,"metadata":{},"outputs":[
                {"data":{"image/png":["iVBORw0K\\n","GgoAAAA=\\n"],"text/plain":["<Figure>"]},
                 "metadata":{},"output_type":"display_data"}],"source":[]}],
                "metadata":{},"nbformat":4,"nbformat_minor":5}
                """);

        Output output = notebook.cell(0).outputs().get(0);

        assertEquals("iVBORw0KGgoAAAA=", output.png().orElseThrow());
        assertEquals("<Figure>", output.text());
    }

    // ---------------------------------------------------------------- old and broken files

    @Test
    void readsAnNbformat3NotebookOutOfItsWorksheets() throws Exception {
        Notebook notebook = Notebook.read("""
                {
                 "metadata": {"name": "old"},
                 "nbformat": 3,
                 "nbformat_minor": 0,
                 "worksheets": [
                  {
                   "cells": [
                    {"cell_type": "markdown", "source": "# Old"},
                    {"cell_type": "code", "collapsed": false, "input": ["x = 1"],
                     "language": "python", "metadata": {}, "outputs": [], "prompt_number": 1}
                   ],
                   "metadata": {}
                  }
                 ]
                }
                """);

        assertEquals(2, notebook.cellCount());
        assertEquals(CellKind.MARKDOWN, notebook.cell(0).kind());
        assertEquals("# Old", notebook.cell(0).source());
        assertEquals(3, notebook.nbformat());
    }

    @Test
    void writesAnNbformat3NotebookBackIntoItsWorksheet() throws Exception {
        String json = """
                {"metadata":{},"nbformat":3,"nbformat_minor":0,"worksheets":[
                 {"cells":[{"cell_type":"markdown","source":"# Old"}],"metadata":{}}]}
                """;
        Notebook notebook = Notebook.read(json);

        notebook.insert(1, CellKind.MARKDOWN, "more");

        JsonObject written = JsonParser.parseString(notebook.toJson()).getAsJsonObject();
        assertFalse(written.has("cells"), "no top-level cells array is invented");
        assertEquals(2, written.getAsJsonArray("worksheets").get(0).getAsJsonObject()
                .getAsJsonArray("cells").size());
    }

    @Test
    void keepsEachCellInTheWorksheetItCameFrom() throws Exception {
        Notebook notebook = Notebook.read("""
                {"metadata":{},"nbformat":3,"nbformat_minor":0,"worksheets":[
                 {"cells":[{"cell_type":"markdown","source":"one"}]},
                 {"cells":[{"cell_type":"markdown","source":"two"}]}]}
                """);

        assertEquals(2, notebook.cellCount());
        notebook.cell(1).setSource("second");

        var worksheets = JsonParser.parseString(notebook.toJson()).getAsJsonObject()
                .getAsJsonArray("worksheets");
        assertEquals("one", Output.multiline(worksheets.get(0).getAsJsonObject()
                .getAsJsonArray("cells").get(0).getAsJsonObject().get("source")));
        assertEquals("second", Output.multiline(worksheets.get(1).getAsJsonObject()
                .getAsJsonArray("cells").get(0).getAsJsonObject().get("source")));
    }

    @Test
    void refusesJsonThatIsNotFinished() {
        assertThrows(NotebookFormatException.class, () -> Notebook.read("{\"cells\": [{\"cell_"));
    }

    /**
     * The file somebody actually has: a {@code .ipynb} created empty, with Python typed
     * straight into it. It must be refused - and said to be refused in words that name
     * what is wrong - rather than opened as an empty notebook that the first save would
     * write over the top of.
     */
    @Test
    void refusesPythonTypedStraightIntoANotebook() {
        NotebookFormatException refused = assertThrows(NotebookFormatException.class,
                () -> Notebook.read("from typing import TypedDict, Annotated, Sequence, Optional\n"));

        assertTrue(refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("json"),
                refused.getMessage());
    }

    @Test
    void saysWhatIsWrongRatherThanJustThatSomethingIs() {
        assertTrue(assertThrows(NotebookFormatException.class,
                () -> Notebook.read("{\"cells\": [{\"cell_")).getMessage()
                .contains("unfinished"));
        assertTrue(assertThrows(NotebookFormatException.class,
                () -> Notebook.read("{\"nbformat\": 4}")).getMessage()
                .contains("cells"));
    }

    /**
     * The message says where to look and nothing else: not the parser's exception class,
     * not its JSONPath, and above all not its advice to enable a lenient parsing mode,
     * which is addressed to a programmer and not to somebody looking at their own file.
     */
    @Test
    void theMessageIsAboutTheFileAndNotAboutTheJsonLibrary() {
        String message = assertThrows(NotebookFormatException.class,
                () -> Notebook.read("from typing import TypedDict\n")).getMessage();

        assertFalse(message.contains("com.google.gson"), message);
        assertFalse(message.toLowerCase(java.util.Locale.ROOT).contains("lenient"), message);
        assertFalse(message.contains("http"), message);
    }

    @Test
    void whereTheJsonBrokeIsKeptBecauseItSaysWhereToLook() {
        assertEquals("the JSON is broken or unfinished, at line 1, column 7",
                Notebook.brokenAt("com.google.gson.stream.MalformedJsonException: Use"
                        + " JsonReader.setStrictness(Strictness.LENIENT) to accept malformed"
                        + " JSON at line 1 column 7 path $\nSee https://github.com/google/gson"));
    }

    @Test
    void aParserThatSaysNothingUsefulStillGivesAReadableMessage() {
        assertEquals("the JSON is broken or unfinished", Notebook.brokenAt(null));
        assertEquals("the JSON is broken or unfinished", Notebook.brokenAt("something else"));
    }

    /** An empty file is somebody who has just created one, not a broken notebook. */
    @Test
    void anEmptyFileIsNotAnError() throws Exception {
        assertEquals(1, Notebook.empty("python").cellCount());
    }

    @Test
    void refusesSomethingThatIsNotANotebook() {
        assertThrows(NotebookFormatException.class, () -> Notebook.read("[1, 2, 3]"));
        assertThrows(NotebookFormatException.class, () -> Notebook.read("{\"hello\": \"world\"}"));
        assertThrows(NotebookFormatException.class, () -> Notebook.read(""));
        assertThrows(NotebookFormatException.class, () -> Notebook.read(null));
    }

    @Test
    void refusesWorksheetsWithNoCellsAnywhere() {
        assertThrows(NotebookFormatException.class,
                () -> Notebook.read("{\"nbformat\":3,\"worksheets\":[{\"metadata\":{}}]}"));
    }

    @Test
    void anEmptyNotebookIsAValidOneWithACellToTypeIn() throws Exception {
        Notebook created = Notebook.empty("python");

        assertEquals(1, created.cellCount());
        assertEquals(CellKind.CODE, created.cell(0).kind());
        assertEquals("python", created.language().orElseThrow());
        // And it reads back.
        Notebook again = Notebook.read(created.toJson());
        assertEquals(1, again.cellCount());
        assertEquals(4, again.nbformat());
        assertEquals(5, again.nbformatMinor());
    }

    @Test
    void aCellTypeNothingKnowsIsTreatedAsProseAndStillSaysWhatItWas() throws Exception {
        Notebook notebook = Notebook.read("""
                {"cells":[{"cell_type":"heading","level":1,"metadata":{},"source":"Title"}],
                 "metadata":{},"nbformat":4,"nbformat_minor":5}
                """);

        assertEquals(CellKind.MARKDOWN, notebook.cell(0).kind());
        assertEquals("heading", JsonParser.parseString(notebook.toJson()).getAsJsonObject()
                .getAsJsonArray("cells").get(0).getAsJsonObject().get("cell_type").getAsString());
    }

    @Test
    void aNotebookOfAnotherLanguageSaysSo() throws Exception {
        Notebook notebook = Notebook.read("""
                {"cells":[],"metadata":{"kernelspec":{"language":"R","name":"ir"}},
                 "nbformat":4,"nbformat_minor":5}
                """);

        assertEquals("r", notebook.language().orElseThrow());
        assertFalse(notebook.isPython());
    }
}
