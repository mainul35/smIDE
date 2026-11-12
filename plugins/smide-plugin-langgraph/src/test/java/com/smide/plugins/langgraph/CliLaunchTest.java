package com.smide.plugins.langgraph;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliLaunchTest {

    private static final Path LAUNCHER = Path.of("C:/project/.venv/Scripts/langgraph.exe");
    private static final Path PYTHON = Path.of("C:/project/.venv/Scripts/python.exe");

    /** Whatever is listed answers; everything else does not. */
    private static Predicate<List<String>> only(String... answering) {
        List<String> answers = List.of(answering);
        return command -> answers.contains(String.join(" ", command));
    }

    @Test
    void theLauncherIsPreferredWhenItRuns() {
        Optional<List<String>> start = CliLaunch.prefer(Optional.of(LAUNCHER), Optional.of(PYTHON),
                only(LAUNCHER + " --help"));
        assertEquals(List.of(LAUNCHER.toString()), start.orElseThrow());
    }

    @Test
    void theModuleIsUsedWhenTheLauncherWillNotStart() {
        // What Smart App Control does to an unsigned launcher: it is there, and it does not run.
        Optional<List<String>> start = CliLaunch.prefer(Optional.of(LAUNCHER), Optional.of(PYTHON),
                only(PYTHON + " -m langgraph_cli --help"));
        assertEquals(List.of(PYTHON.toString(), "-m", "langgraph_cli"), start.orElseThrow());
    }

    @Test
    void theModuleIsUsedWhenThereIsNoLauncherAtAll() {
        Optional<List<String>> start = CliLaunch.prefer(Optional.empty(), Optional.of(PYTHON),
                only(PYTHON + " -m langgraph_cli --help"));
        assertEquals(List.of(PYTHON.toString(), "-m", "langgraph_cli"), start.orElseThrow());
    }

    @Test
    void theModuleThatDoesNothingIsNotOfferedAsTheCli() {
        // langgraph_cli.cli imports and exits 0 in silence; it must never be what gets run.
        assertTrue(CliLaunch.prefer(Optional.of(LAUNCHER), Optional.of(PYTHON),
                only(PYTHON + " -m langgraph_cli.cli --help")).isEmpty());
    }

    @Test
    void nothingRunsAndNothingIsPretended() {
        assertTrue(CliLaunch.prefer(Optional.of(LAUNCHER), Optional.of(PYTHON), only()).isEmpty());
        assertTrue(CliLaunch.prefer(Optional.empty(), Optional.empty(), only()).isEmpty());
    }

    @Test
    void aLauncherWithNoInterpreterBehindItIsStillTried() {
        assertEquals(List.of(LAUNCHER.toString()),
                CliLaunch.prefer(Optional.of(LAUNCHER), Optional.empty(), only(LAUNCHER + " --help")).orElseThrow());
        assertTrue(CliLaunch.prefer(Optional.of(LAUNCHER), Optional.empty(), only()).isEmpty());
    }

    @Test
    void whatIsAskedOfEachCandidate() {
        List<String> asked = new ArrayList<>();
        CliLaunch.prefer(Optional.of(LAUNCHER), Optional.of(PYTHON), command -> {
            asked.add(String.join(" ", command));
            return false;
        });
        // Each one is asked for --help, once, launcher first.
        assertEquals(List.of(LAUNCHER + " --help", PYTHON + " -m langgraph_cli --help"), asked);
    }

    @Test
    void aCommandThatCannotBeStartedIsANo() {
        CliLaunch.forget();
        assertFalse(CliLaunch.works(List.of("langgraph-that-is-not-installed-anywhere", "--help")));
    }

    @Test
    void somethingThatDoesRunAndAnswersIsAYes() {
        CliLaunch.forget();
        // The JVM saying what version it is: proof the probe can say yes.
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        assertTrue(CliLaunch.works(List.of(java, "-version")));
    }

    @Test
    void somethingThatRunsAndSaysNothingIsANo() {
        CliLaunch.forget();
        // Exits 0, prints nothing: what langgraph_cli.cli does, and not something to run a server with.
        List<String> silent = com.smide.api.util.Executables.WINDOWS
                ? List.of("cmd", "/c", "exit", "0")
                : List.of("true");
        assertFalse(CliLaunch.works(silent));
    }
}
