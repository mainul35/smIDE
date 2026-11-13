package com.smide.plugins.notebook;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelEventTest {

    @Test
    void readsAMessage() {
        KernelEvent event = KernelEvent.parse("""
                {"type":"message","id":"run-1","msg_type":"stream",
                 "content":{"name":"stdout","text":"hello\\n"}}
                """);

        assertTrue(event.isUnderstood());
        assertTrue(event.is(KernelEvent.MESSAGE));
        assertEquals("run-1", event.id());
        assertEquals("stream", event.msgType());
        assertEquals("hello\n", Output.multiline(event.content().get("text")));
    }

    @Test
    void readsAReplyAndItsCount() {
        KernelEvent event = KernelEvent.parse(
                "{\"type\":\"reply\",\"id\":\"run-2\",\"status\":\"error\",\"execution_count\":4}");

        assertTrue(event.is(KernelEvent.REPLY));
        assertEquals("error", event.status());
        assertEquals(4, event.count().orElseThrow());
    }

    @Test
    void readsAMissingPackage() {
        KernelEvent event = KernelEvent.parse("{\"type\":\"missing\",\"module\":\"ipykernel\"}");

        assertTrue(event.is(KernelEvent.MISSING));
        assertEquals("ipykernel", event.module());
    }

    @Test
    void aMessageWithNoRequestHasNoId() {
        KernelEvent event = KernelEvent.parse(
                "{\"type\":\"message\",\"id\":null,\"msg_type\":\"status\",\"content\":{}}");

        assertTrue(event.isUnderstood());
        assertEquals(null, event.id());
    }

    @Test
    void aCountThatIsNotANumberIsNoCount() {
        assertTrue(KernelEvent.parse("{\"type\":\"reply\",\"execution_count\":null}")
                .count().isEmpty());
    }

    /** A package that prints on import must not be mistaken for the protocol. */
    @Test
    void anythingElseOnStandardOutputIsNotUnderstoodRatherThanFatal()  {
        for (String line : new String[]{"Loading extension ...", "", "[1, 2]", "{\"no\":\"type\"}",
                "{not json at all", null}) {
            KernelEvent event = KernelEvent.parse(line);
            assertFalse(event.isUnderstood(), "should not be understood: " + line);
            assertTrue(event.is(KernelEvent.UNKNOWN));
        }
    }

    @Test
    void theLineThatWasNotUnderstoodIsKeptSoItCanBeLogged() {
        assertEquals("Loading extension ...",
                KernelEvent.parse("Loading extension ...").message());
    }

    // ---------------------------------------------------------------- the command line

    @Test
    void theKernelIsStartedThroughTheInterpreterAndNeverThroughALauncher() {
        Kernel kernel = new Kernel(Path.of("C:\\p\\.venv\\Scripts\\python.exe"),
                Path.of("C:\\home\\tools\\notebook\\kernel_bridge.py"), null, event -> {
        }, line -> {
        });

        assertEquals(java.util.List.of("C:\\p\\.venv\\Scripts\\python.exe", "-u",
                "C:\\home\\tools\\notebook\\kernel_bridge.py"), kernel.command());
        assertFalse(String.join(" ", kernel.command()).contains("jupyter"));
        assertEquals(Kernel.State.STARTING, kernel.state());
    }

    @Test
    void theInstallCommandNamesThisProjectsPythonAndGoesThroughIt() {
        assertEquals("C:\\p\\.venv\\Scripts\\python.exe -m pip install ipykernel",
                KernelHelper.installCommand(Path.of("C:\\p\\.venv\\Scripts\\python.exe")));
    }

    @Test
    void anInterpreterWithASpaceInItsPathIsQuoted() {
        assertEquals("\"C:\\Program Files\\Python\\python.exe\" -m pip install ipykernel",
                KernelHelper.installCommand(Path.of("C:\\Program Files\\Python\\python.exe")));
    }

    @Test
    void theHelperIsInTheJarAndRunsAKernelThroughTheInterpreterItIsRunBy() throws Exception {
        String helper = KernelHelper.text();

        // The kernelspec is overridden, and with this interpreter: see the helper's own
        // comment for why leaving it alone would run a different Python.
        assertTrue(helper.contains("kernel_spec.argv = ["));
        assertTrue(helper.contains("sys.executable, \"-m\", \"ipykernel_launcher\""));
    }
}
