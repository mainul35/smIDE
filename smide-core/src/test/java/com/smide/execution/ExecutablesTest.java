package com.smide.execution;

import com.smide.api.execution.Executables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning the name of a command into the file that can be started.
 *
 * <p>Written after an agent inside the IDE ran {@code mvn -v} on a machine with Maven installed
 * and was told the system could not find the file specified - because what is on the PATH is
 * {@code mvn}, a shell script, and what Windows can start is {@code mvn.cmd} beside it.
 */
class ExecutablesTest {

    @Test
    void aCommandThatCannotBeHelpedIsHandedBackUntouched() {
        // Left to fail with its own message rather than quietly turned into something else.
        List<String> command = List.of("there-is-no-such-command-as-this-one", "status");
        assertSame(command, Executables.runnable(command), "nothing to resolve, nothing to copy");
    }

    @Test
    void aCommandGivenAsAPathIsLeftAlone() {
        List<String> command = List.of(System.getProperty("java.home") + "/bin/java", "-version");
        assertSame(command, Executables.runnable(command), "it already says which file it means");
    }

    @Test
    void nothingIsMadeOfNothing() {
        assertEquals(List.of(), Executables.runnable(List.of()));
        assertFalse(Executables.canRun(""));
        assertFalse(Executables.canRun(null));
        assertFalse(Executables.canRun("there-is-no-such-command-as-this-one"));
    }

    @Test
    void aPathIsJudgedByWhetherItIsThere() {
        assertFalse(Executables.canRun("/no/such/tool"));
        assertTrue(Executables.canRun(System.getProperty("java.home")
                + (OS.WINDOWS.isCurrentOs() ? "\\bin\\java.exe" : "/bin/java")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsABareNameBecomesTheFileWindowsCanStart() {
        // java is on the PATH of any machine running this test, as java.exe.
        String resolved = Executables.runnable(List.of("java", "-version")).get(0);
        assertTrue(resolved.toLowerCase().endsWith("java.exe"), resolved);
        assertTrue(Executables.canRun("java"));
        assertEquals(List.of("java", "-version").subList(1, 2),
                Executables.runnable(List.of("java", "-version")).subList(1, 2),
                "only the command itself is rewritten");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheResolvedCommandActuallyStarts() throws Exception {
        Process started = new ProcessBuilder(Executables.runnable(List.of("java", "-version")))
                .redirectErrorStream(true).start();
        String output = new String(started.getInputStream().readAllBytes());
        assertEquals(0, started.waitFor());
        assertFalse(output.isBlank());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void elsewhereTheNameIsTheName() {
        List<String> command = List.of("java", "-version");
        assertSame(command, Executables.runnable(command));
        assertTrue(Executables.canRun("sh"));
    }

    @Test
    void aCommandIsLookedForOnThePathItWillBeStartedWith() {
        /* The bug this exists for, in one line. The IDE's PATH and the developer's shell's PATH
           are different lists, and a command resolved against one and run with the other is found
           where it is not and missing where it is. An empty PATH is the clearest possible case:
           nothing is on it, so nothing is found on it, whatever this process happens to have. */
        assertNull(Executables.find("java", ""));
        assertFalse(Executables.canRun("java", ""), "nothing is on an empty PATH");
    }

    @Test
    void aPathGivenAsNullMeansThisProcessOwn() {
        assertEquals(Executables.canRun("java"), Executables.canRun("java", null));
    }

    @Test
    void thePathInAnEnvironmentIsFoundWhicheverCaseItIsSpelledIn() {
        // Windows hands it out as Path and a Unix-shaped shell as PATH. A map is neither.
        assertEquals("/one", Executables.pathIn(Map.of("PATH", "/one")));
        assertEquals("/two", Executables.pathIn(Map.of("Path", "/two")));
        assertEquals("/three", Executables.pathIn(Map.of("path", "/three")));
    }

    @Test
    void anEnvironmentWithNoPathInItFallsBackToThisProcessOwn() {
        assertEquals(System.getenv().getOrDefault("PATH", ""), Executables.pathIn(Map.of()));
        assertEquals(System.getenv().getOrDefault("PATH", ""), Executables.pathIn(null));
    }

    @Test
    void aCommandThatIsOnlyOnTheOtherPathIsResolvedAgainstIt() {
        // The shape of what the assistant now does: a PATH the IDE does not have, holding the
        // thing the developer's profile put there.
        String home = System.getProperty("java.home");
        assertTrue(Executables.canRun("java", home + "/bin"), "java lives in its own home");
        List<String> resolved = Executables.runnable(List.of("java", "-version"), home + "/bin");
        String started = resolved.get(0).toLowerCase(java.util.Locale.ROOT);
        assertTrue(started.endsWith("java.exe") || started.endsWith("java"), resolved.toString());
    }
}
