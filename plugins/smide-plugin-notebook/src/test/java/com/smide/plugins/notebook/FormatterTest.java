package com.smide.plugins.notebook;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatterTest {

    private static final Path PYTHON = Path.of("C:\\project\\.venv\\Scripts\\python.exe");

    @Test
    void ruffIsRunThroughTheInterpreterAndNeverThroughItsLauncher() {
        List<String> command = Formatter.command(PYTHON, "cell-1.py");
        assertEquals(PYTHON.toString(), command.get(0));
        assertEquals(List.of("-m", "ruff", "format"), command.subList(1, 4));
        // Reading from stdin: a cell has no file of its own to point at.
        assertEquals("-", command.get(command.size() - 1));
        assertTrue(command.contains("--stdin-filename"));
        assertFalse(String.join(" ", command).contains("ruff.exe"),
                "a pip launcher is what Smart App Control blocks");
    }

    @Test
    void installingGoesThroughTheInterpreterToo() {
        List<String> command = Formatter.installCommand(PYTHON);
        assertEquals(List.of(PYTHON.toString(), "-m", "pip", "install", Formatter.PACKAGE), command);
        assertFalse(String.join(" ", command).contains("pip.exe"));
    }

    @Test
    void theVersionIsPinnedSoAReleaseCannotChangeTheOutput() {
        assertTrue(Formatter.PACKAGE.startsWith("ruff>="), Formatter.PACKAGE);
        assertTrue(Formatter.PACKAGE.contains("<"), "an upper bound keeps formatting stable");
    }

    @Test
    void aCellIsNamedAfterItsNotebookAndItsPosition() {
        assertEquals("my_agent-cell-3.py",
                Formatter.nameFor(Path.of("C:\\work\\my_agent.ipynb"), 2));
        assertEquals("notebook-cell-1.py", Formatter.nameFor(null, 0));
        // The name must end in .py, or ruff will not treat it as Python.
        assertTrue(Formatter.nameFor(Path.of("a.ipynb"), 0).endsWith(".py"));
    }

    @Test
    void aCellThatIsNotWholePythonIsToldApartFromAToolThatFailed() {
        // Word for word what ruff 0.14 printed for a cell holding a fragment of a block.
        assertTrue(Formatter.cannotParse(
                "error: Failed to parse cell-2.py:1:1: Unexpected indentation"));
        assertTrue(Formatter.cannotParse("error: Failed to parse cell-1.py:2:1: Expected an expression"));
        assertTrue(Formatter.cannotParse("SyntaxError: invalid syntax"));
        assertFalse(Formatter.cannotParse("error: Permission denied"));
        assertFalse(Formatter.cannotParse(""));
        assertFalse(Formatter.cannotParse(null));
    }

    @Test
    void aMissingRuffIsItsOwnComplaint() {
        assertTrue(Formatter.notInstalled("C:\\python.exe: No module named ruff"));
        assertTrue(Formatter.notInstalled("No module named 'ruff'"));
        assertFalse(Formatter.notInstalled("No module named 'black'"));
        assertFalse(Formatter.notInstalled(null));
    }

    @Test
    void withNoInterpreterThereIsNothingToFormatWith() {
        Formatter.Formatted answer = Formatter.format(null, "x=1", "cell-1.py");
        assertFalse(answer.worked());
        assertTrue(answer.problem().orElseThrow().contains("no Python interpreter"));
    }

    @Test
    void anEmptyCellIsLeftExactlyAsItIs() {
        assertEquals("", Formatter.format(PYTHON, "", "cell-1.py").text().orElseThrow());
        assertEquals("   ", Formatter.format(PYTHON, "   ", "cell-1.py").text().orElseThrow());
    }

    @Test
    void aRealRuffTidiesACellAndRefusesAFragment() {
        Path python = Path.of("C:\\Users\\mainu\\Documents\\codes\\langgraph_bootcamp",
                ".venv", "Scripts", "python.exe");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(python),
                "no environment with ruff here");
        Formatter.Formatted tidy = Formatter.format(python,
                "def  plan( state ):\n  return {\"notes\":[\"planning\"   ,  \"more\"]}\n",
                "cell-1.py");
        org.junit.jupiter.api.Assumptions.assumeFalse(
                tidy.problem().orElse("").contains("not installed"), "ruff is not installed");
        assertTrue(tidy.worked(), tidy.problem().orElse(""));
        assertEquals("def plan(state):\n    return {\"notes\": [\"planning\", \"more\"]}\n",
                tidy.text().orElseThrow());

        // A cell that is a fragment of a block is not mangled and is not emptied.
        Formatter.Formatted fragment = Formatter.format(python, "    return state\n", "cell-2.py");
        assertFalse(fragment.worked());
        assertTrue(fragment.problem().orElseThrow().contains("not complete Python"),
                fragment.problem().orElseThrow());
    }

    @Test
    void ruffIsLookedForBesideTheInterpreter() {
        assertTrue(Formatter.in(null).isEmpty());
        assertTrue(Formatter.in(Path.of("python.exe")).isEmpty());
    }
}
