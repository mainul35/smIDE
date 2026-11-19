package com.smide.api.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asking the developer's shell what environment it has.
 *
 * <p>Written after the assistant told a developer there was no JDK and no Maven on their machine,
 * in a window whose Terminal tab was printing the version of both. The IDE had the environment the
 * desktop handed it at login; the terminal had the one the profile builds. Everything here is
 * about those two not being the same thing.
 */
class ShellTest {

    @Test
    void theShellIsAskedForItsEnvironmentInItsOwnLanguage() {
        // PowerShell has no env(1) and a Bourne shell has no Get-ChildItem. Asking the wrong one
        // does not fail loudly - it prints an error and exits, and the environment comes back
        // empty, which reads exactly like a machine with nothing installed on it.
        assertTrue(Shell.dumpFor("pwsh.exe").contains("Env:"));
        assertTrue(Shell.dumpFor("C:\\Program Files\\PowerShell\\7\\powershell.exe").contains("Env:"));
        assertEquals("env", Shell.dumpFor("/bin/zsh"));
        assertEquals("env", Shell.dumpFor("bash"));
        assertEquals("set", Shell.dumpFor("cmd.exe"));
    }

    @Test
    void aShellIsAskedWithTheFlagThatStillReadsTheProfile() {
        List<String> powershell = Shell.asking("pwsh.exe", "echo hi");
        assertEquals(List.of("pwsh.exe", "-NoLogo", "-NonInteractive", "-Command", "echo hi"), powershell);
        // -NoProfile is the one flag that would undo the whole point of going through a shell.
        assertFalse(powershell.contains("-NoProfile"));
        assertEquals(List.of("/bin/bash", "-lc", "echo hi"), Shell.asking("/bin/bash", "echo hi"));
        assertEquals(List.of("cmd.exe", "/c", "echo hi"), Shell.asking("cmd.exe", "echo hi"));
    }

    @Test
    void theFlagsForATabNobodyIsTypingIntoAreLeftOff() {
        // -l and -NoLogo belong to an interactive tab; a one-line question gets its own.
        List<String> asked = Shell.asking("/bin/zsh", "env");
        assertEquals(List.of("/bin/zsh", "-lc", "env"), asked);
    }

    @Test
    void whatAShellPrintsBecomesAnEnvironment() {
        Map<String, String> found = Shell.parse("PATH=/usr/bin:/opt/maven/bin\nHOME=/home/dev\n");
        assertEquals("/usr/bin:/opt/maven/bin", found.get("PATH"));
        assertEquals("/home/dev", found.get("HOME"));
    }

    @Test
    void aValueWithAnEqualsSignInItKeepsAllOfIt() {
        // Perfectly ordinary in JAVA_TOOL_OPTIONS, and splitting on the last = would ruin it.
        assertEquals("-Dfile.encoding=UTF-8",
                Shell.parse("JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8").get("JAVA_TOOL_OPTIONS"));
    }

    @Test
    void windowsNamesWithBracketsInThemAreNames() {
        assertEquals("C:\\Program Files (x86)",
                Shell.parse("ProgramFiles(x86)=C:\\Program Files (x86)").get("ProgramFiles(x86)"));
    }

    @Test
    void aProfileSayingGoodMorningIsNotAVariable() {
        // Profiles print things. A banner, a fortune, a warning about a deprecated alias - none
        // of it is an environment, and none of it must be allowed to look like one.
        Map<String, String> found = Shell.parse(
                "Welcome back, dev!\n\nPATH=/usr/bin\n  nvm: using node 20\n=nothing\n1BAD=x\n");
        assertEquals(Map.of("PATH", "/usr/bin"), found);
    }

    @Test
    void nothingPrintedIsNoEnvironmentRatherThanAnExplosion() {
        assertTrue(Shell.parse("").isEmpty());
        assertTrue(Shell.parse(null).isEmpty());
    }

    @Test
    void anEmptyAnswerFallsBackToTheIdesOwnEnvironment() {
        // The shell is not there, or it hung, or it printed nonsense. That is where things stood
        // before anybody thought to ask it, so it is where they go back to.
        Shell.forget();
        Map<String, String> found = Shell.environment("there-is-no-such-shell-as-this-one");
        assertEquals(System.getenv().get("PATH"), found.get("PATH"));
        Shell.forget();
    }

    @Test
    void aConfiguredShellWinsOverThePlatformDefault() {
        assertEquals("/usr/local/bin/fish", Shell.interactive("/usr/local/bin/fish")[0]);
        assertEquals("/usr/local/bin/fish", Shell.interactive("   /usr/local/bin/fish  ")[0]);
    }

    @Test
    void aPathWithSpacesInItIsNotTwoWords(@TempDir Path dir) throws Exception {
        // A whole line that is a file is a file, however many spaces are in its name.
        Path shell = Files.writeString(dir.resolve("my shell.exe"), "");
        assertEquals(1, Shell.split(shell.toString()).length, shell.toString());
        // With arguments after it, the quotes are what hold it together.
        String[] quoted = Shell.split("\"" + shell + "\" -NoLogo");
        assertEquals(shell.toString(), quoted[0]);
        assertEquals("-NoLogo", quoted[1]);
        // And unquoted it is split on the spaces, which is long-standing behaviour and not new.
        assertTrue(Shell.split(shell + " -NoLogo").length > 2, "a space is a space");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theDefaultOnWindowsIsAPowerShell() {
        assertTrue(Shell.defaultDescription().contains("powershell")
                || Shell.defaultDescription().contains("pwsh"), Shell.defaultDescription());
        String chosen = Shell.interactive("")[0].toLowerCase(java.util.Locale.ROOT);
        assertTrue(chosen.contains("powershell") || chosen.contains("pwsh"), chosen);
        assertEquals("-NoLogo", Shell.interactive("")[1]);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theDefaultElsewhereIsTheLoginShellOrBash() {
        String shell = System.getenv("SHELL");
        assertEquals(shell == null || shell.isBlank() ? "/bin/bash" : shell, Shell.interactive("")[0]);
    }

    @Test
    void theRealShellCanBeAskedAndAnswersWithAPath() {
        /* The whole thing end to end, against whatever shell this machine has: start it, let the
           profile run, read what it prints. A PATH is the one variable every shell on every
           platform has, so it is the one thing worth insisting on. If this ever fails, the
           assistant is back to running commands in the IDE's environment - which is not a crash,
           only a quieter version of the bug this was written for. */
        Shell.forget();
        String path = Shell.path("");
        assertFalse(path.isBlank(), "the shell answered with no PATH at all");
        Shell.forget();
    }

    @Test
    void theAnswerIsRememberedRatherThanAskedForAgain() {
        // One process per IDE, not one per command. A profile can take seconds.
        Shell.forget();
        Map<String, String> once = Shell.environment("");
        assertSame(once, Shell.environment(""), "the shell was started a second time");
        Shell.forget();
    }

    @Test
    void whatTheShellHasCanBeAddedTo() {
        Map<String, String> all = Shell.environmentWith("", Map.of("TERM", "xterm-256color"));
        assertEquals("xterm-256color", all.get("TERM"));
        assertTrue(all.containsKey("PATH") || all.containsKey("Path"));
    }
}
