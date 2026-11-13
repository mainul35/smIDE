package com.smide.plugins.notebook;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntaxCheckTest {

    /** An interpreter to ask, when this machine has one; the tests that need it say so. */
    private static Optional<Path> python() {
        for (String candidate : new java.util.ArrayList<>(java.util.List.of(
                "C:\\Users\\mainu\\Documents\\codes\\langgraph_bootcamp\\.venv\\Scripts\\python.exe",
                "python3", "python"))) {
            try {
                Path path = Path.of(candidate);
                if (Files.isRegularFile(path)) {
                    return Optional.of(path);
                }
                Process probe = new ProcessBuilder(candidate, "-c", "pass").start();
                if (probe.waitFor() == 0) {
                    return Optional.of(Path.of(candidate));
                }
            } catch (Exception ignored) {
                // Try the next one.
            }
        }
        return Optional.empty();
    }

    @Test
    void whatIsWorthChecking() {
        assertTrue(SyntaxCheck.worthChecking("x = 1"));
        assertFalse(SyntaxCheck.worthChecking(""));
        assertFalse(SyntaxCheck.worthChecking("   \n\n  "));
        assertFalse(SyntaxCheck.worthChecking(null));
        // Magics and shell lines are ordinary in a notebook and are not Python.
        assertFalse(SyntaxCheck.worthChecking("%%capture"));
        assertFalse(SyntaxCheck.worthChecking("!pip install langgraph\n%matplotlib inline"));
        // But a cell that has both is still worth asking about.
        assertTrue(SyntaxCheck.worthChecking("!pip install x\nimport x"));
    }

    @Test
    void whatTheCheckingProgramPrintsIsRead() {
        SyntaxCheck.Problem problem = SyntaxCheck.parse("3\t11\t'(' was never closed").orElseThrow();
        assertEquals(3, problem.line());
        assertEquals(11, problem.column());
        assertEquals("'(' was never closed", problem.message());
    }

    @Test
    void nothingPrintedMeansNothingWrong() {
        assertEquals(Optional.empty(), SyntaxCheck.parse(""));
        assertEquals(Optional.empty(), SyntaxCheck.parse("   "));
        assertEquals(Optional.empty(), SyntaxCheck.parse(null));
        assertEquals(Optional.empty(), SyntaxCheck.parse("something unexpected entirely"));
        assertEquals(Optional.empty(), SyntaxCheck.parse("x\ty\tz"));
    }

    @Test
    void aPositionIsNeverBeforeTheStartOfTheFile() {
        // Python reports 0 for some errors; a column of 0 would be off the front of the line.
        SyntaxCheck.Problem problem = SyntaxCheck.parse("0\t0\tunexpected EOF").orElseThrow();
        assertEquals(1, problem.line());
        assertEquals(1, problem.column());
    }

    @Test
    void theProgramSurvivesBeingAnArgument() {
        String argument = SyntaxCheck.argument();
        /* The thing that actually broke this: Windows hands a process one command string,
           so a program with newlines and quotes in it arrives mangled and reports nothing -
           which is indistinguishable from a cell with nothing wrong. */
        assertFalse(argument.contains("\n"), argument);
        assertFalse(argument.contains("\""), argument);
        assertTrue(argument.startsWith("import base64;exec("));
    }

    @Test
    void aRealInterpreterFindsARealMistake() {
        Optional<Path> python = python();
        org.junit.jupiter.api.Assumptions.assumeTrue(python.isPresent(), "no Python here");
        SyntaxCheck.Problem problem = SyntaxCheck.check(python.get(), """
                def plan(state):
                    return {"notes": ["planning"
                """).orElseThrow(() -> new AssertionError("an unclosed bracket should be found"));
        assertTrue(problem.line() >= 1);
        assertFalse(problem.message().isBlank());
    }

    @Test
    void aRealInterpreterPassesGoodCode() {
        Optional<Path> python = python();
        org.junit.jupiter.api.Assumptions.assumeTrue(python.isPresent(), "no Python here");
        assertEquals(Optional.empty(), SyntaxCheck.check(python.get(), """
                def plan(state):
                    return {"notes": ["planning an answer"]}
                """));
    }

    @Test
    void checkingRunsNothingInTheCell() {
        Optional<Path> python = python();
        org.junit.jupiter.api.Assumptions.assumeTrue(python.isPresent(), "no Python here");
        /* The cell parses, so there is no problem - and the file it would have written if
           anything had been executed is not there. compile() builds code; it does not run it. */
        Path witness = Path.of(System.getProperty("java.io.tmpdir"), "smide-syntax-check-witness");
        try {
            Files.deleteIfExists(witness);
        } catch (java.io.IOException ignored) {
            // Nothing to clean up.
        }
        assertEquals(Optional.empty(), SyntaxCheck.check(python.get(),
                "from pathlib import Path\nPath(r\"" + witness.toString().replace("\\", "\\\\")
                        + "\").write_text(\"it ran\")\n"));
        assertFalse(Files.exists(witness), "compile() must not execute the cell");
    }
}
