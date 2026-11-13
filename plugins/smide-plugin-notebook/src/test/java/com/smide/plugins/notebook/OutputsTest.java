package com.smide.plugins.notebook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputsTest {

    private static JsonObject content(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void streamTextBecomesAStreamOutput() {
        Outputs.Effect effect = Outputs.effectOf("stream",
                content("{\"name\":\"stderr\",\"text\":\"oh dear\\n\"}"));

        Output output = assertInstanceOf(Outputs.Effect.Add.class, effect).output();
        assertEquals(Output.STREAM, output.outputType());
        assertEquals("stderr", output.streamName());
        assertEquals("oh dear\n", output.text());
        assertTrue(output.isStandardError());
    }

    @Test
    void aResultKeepsItsCountSoItCanBeLabelled() {
        Outputs.Effect effect = Outputs.effectOf("execute_result",
                content("{\"data\":{\"text/plain\":\"42\"},\"metadata\":{},\"execution_count\":5}"));

        Output output = assertInstanceOf(Outputs.Effect.Add.class, effect).output();
        assertEquals(Output.EXECUTE_RESULT, output.outputType());
        assertEquals("42", output.text());
        assertEquals(5, output.executionCount().orElseThrow());
    }

    @Test
    void aPictureArrivesAsDisplayData() {
        Outputs.Effect effect = Outputs.effectOf("display_data",
                content("{\"data\":{\"image/png\":\"iVBORw0K\",\"text/plain\":\"<Figure>\"},"
                        + "\"metadata\":{\"image/png\":{\"width\":400}}}"));

        Output output = assertInstanceOf(Outputs.Effect.Add.class, effect).output();
        assertEquals(Output.DISPLAY_DATA, output.outputType());
        assertEquals("iVBORw0K", output.png().orElseThrow());
        assertEquals("<Figure>", output.text());
        // The metadata the kernel sent is kept, because a notebook that had it must keep it.
        assertTrue(output.json().getAsJsonObject("metadata").has("image/png"));
    }

    /** Text parts are stored as nbformat stores them, so a diff against Jupyter is quiet. */
    @Test
    void aResultsTextIsWrittenAsLinesAndItsPictureIsNot() {
        Outputs.Effect effect = Outputs.effectOf("execute_result",
                content("{\"data\":{\"text/plain\":\"one\\ntwo\",\"image/png\":\"iVBORw0K\"},"
                        + "\"metadata\":{},\"execution_count\":1}"));

        JsonObject data = assertInstanceOf(Outputs.Effect.Add.class, effect)
                .output().json().getAsJsonObject("data");
        assertEquals(2, data.getAsJsonArray("text/plain").size());
        assertEquals("one\n", data.getAsJsonArray("text/plain").get(0).getAsString());
        assertEquals("two", data.getAsJsonArray("text/plain").get(1).getAsString());
        assertEquals("iVBORw0K", data.get("image/png").getAsString());
        // And it still reads back as the one string it was.
        assertEquals("one\ntwo", assertInstanceOf(Outputs.Effect.Add.class, effect).output().text());
    }

    @Test
    void aMimeTypeNothingKnowsIsStoredExactlyAsItArrived() {
        Outputs.Effect effect = Outputs.effectOf("display_data",
                content("{\"data\":{\"application/vnd.plotly.v1+json\":{\"layout\":{}}},"
                        + "\"metadata\":{}}"));

        JsonObject data = assertInstanceOf(Outputs.Effect.Add.class, effect)
                .output().json().getAsJsonObject("data");
        assertTrue(data.getAsJsonObject("application/vnd.plotly.v1+json").has("layout"));
    }

    @Test
    void anErrorKeepsItsTraceback() {
        Outputs.Effect effect = Outputs.effectOf("error",
                content("{\"ename\":\"ZeroDivisionError\",\"evalue\":\"division by zero\","
                        + "\"traceback\":[\"Traceback...\",\"ZeroDivisionError: division by zero\"]}"));

        Output output = assertInstanceOf(Outputs.Effect.Add.class, effect).output();
        assertEquals(Output.ERROR, output.outputType());
        assertEquals("ZeroDivisionError", output.errorName());
        assertEquals("division by zero", output.errorValue());
        assertEquals(2, output.traceback().size());
    }

    @Test
    void theKernelEchoingTheInputIsWhereTheCountComesFrom() {
        Outputs.Effect effect = Outputs.effectOf("execute_input",
                content("{\"code\":\"1+1\",\"execution_count\":9}"));

        assertEquals(9, assertInstanceOf(Outputs.Effect.Counted.class, effect).executionCount());
    }

    @Test
    void clearOutputMeansClear() {
        assertInstanceOf(Outputs.Effect.Clear.class,
                Outputs.effectOf("clear_output", content("{\"wait\":true}")));
    }

    @Test
    void messagesThatAreNotOutputAreNothingToStore() {
        for (String type : new String[]{"status", "execute_reply", "comm_open", "unknown", null}) {
            assertInstanceOf(Outputs.Effect.None.class, Outputs.effectOf(type, new JsonObject()),
                    "should be nothing: " + type);
        }
    }

    @Test
    void idleIsReadOffAStatusMessage() {
        assertTrue(Outputs.idleFrom("status", content("{\"execution_state\":\"idle\"}")).orElseThrow());
        assertEquals(false,
                Outputs.idleFrom("status", content("{\"execution_state\":\"busy\"}")).orElseThrow());
        assertTrue(Outputs.idleFrom("stream", content("{}")).isEmpty());
        assertTrue(Outputs.idleFrom("status", content("{}")).isEmpty());
    }

    // ---------------------------------------------------------------- folding

    /** A loop that prints sends one message per line; the cell must not grow one output each. */
    @Test
    void consecutiveTextOnTheSameStreamBecomesOneOutput() {
        List<Output> outputs = List.of();
        for (int i = 1; i <= 3; i++) {
            outputs = Outputs.append(outputs, Output.stream("stdout", i + "\n"));
        }

        assertEquals(1, outputs.size());
        assertEquals("1\n2\n3\n", outputs.get(0).text());
    }

    @Test
    void standardErrorIsNotFoldedIntoStandardOut() {
        List<Output> outputs = Outputs.append(List.of(), Output.stream("stdout", "fine\n"));

        outputs = Outputs.append(outputs, Output.stream("stderr", "warning\n"));
        outputs = Outputs.append(outputs, Output.stream("stdout", "fine again\n"));

        assertEquals(3, outputs.size());
        assertEquals("stdout", outputs.get(0).streamName());
        assertEquals("stderr", outputs.get(1).streamName());
        assertEquals("stdout", outputs.get(2).streamName());
    }

    @Test
    void aResultIsNeverFoldedIntoTheTextBeforeIt() {
        List<Output> outputs = Outputs.append(List.of(), Output.stream("stdout", "printed\n"));

        outputs = Outputs.append(outputs, Output.result(1, content("{\"text/plain\":\"42\"}"), null));

        assertEquals(2, outputs.size());
        assertEquals("42", outputs.get(1).text());
    }

    @Test
    void foldedTextSurvivesBeingWrittenToANotebook() throws Exception {
        Notebook notebook = Notebook.read("""
                {"cells":[{"cell_type":"code","execution_count":null,"metadata":{},
                "outputs":[],"source":[]}],"metadata":{},"nbformat":4,"nbformat_minor":5}
                """);
        List<Output> outputs = Outputs.append(
                Outputs.append(List.of(), Output.stream("stdout", "a\n")),
                Output.stream("stdout", "b\n"));

        notebook.cell(0).setOutputs(outputs);

        assertEquals("a\nb\n", Notebook.read(notebook.toJson()).cell(0).outputs().get(0).text());
    }
}
