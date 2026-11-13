package com.smide.plugins.langgraph;

import com.smide.api.util.Executables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentsTest {

    @TempDir
    Path dir;

    /** A directory shaped like a virtual environment, with an interpreter where one belongs. */
    private Path venv(Path project, String name) throws IOException {
        Path bin = project.resolve(name).resolve(Executables.WINDOWS ? "Scripts" : "bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve(Executables.WINDOWS ? "python.exe" : "python"), "");
        return project.resolve(name);
    }

    @Test
    void anEnvironmentIsFoundByItsInterpreter() throws IOException {
        Path project = Files.createDirectories(dir.resolve("one"));
        Path venv = venv(project, ".venv");
        assertEquals(List.of(venv), Environments.venvsIn(List.of(project)));
        assertTrue(Environments.interpreterIn(venv).isPresent());
    }

    @Test
    void aFolderCalledVenvWithNothingInItIsNotOne() throws IOException {
        Path project = Files.createDirectories(dir.resolve("two"));
        Files.createDirectories(project.resolve(".venv"));
        assertEquals(List.of(), Environments.venvsIn(List.of(project)));
        assertEquals(Optional.empty(), Environments.interpreterIn(project.resolve(".venv")));
    }

    @Test
    void theOtherNamesPythonPeopleUseAreFoundToo() throws IOException {
        Path project = Files.createDirectories(dir.resolve("three"));
        Path venv = venv(project, "venv");
        assertEquals(List.of(venv), Environments.venvsIn(List.of(project)));
    }

    @Test
    void theSameEnvironmentIsOnlyOfferedOnce() throws IOException {
        Path project = Files.createDirectories(dir.resolve("four"));
        Path venv = venv(project, ".venv");
        assertEquals(List.of(venv), Environments.venvsIn(List.of(project, project, project)));
    }

    @Test
    void nothingAtAllIsNotAProblem() {
        assertEquals(List.of(), Environments.venvsIn(List.of()));
        assertEquals(List.of(), Environments.venvsIn(java.util.Arrays.asList((Path) null)));
        assertEquals(List.of(), Environments.venvsIn(List.of(dir.resolve("never-existed"))));
        assertEquals(Optional.empty(), Environments.interpreterIn(null));
        assertFalse(Environments.carriesLangGraph(null));
    }

    @Test
    void anEnvironmentIsNamedAfterTheProjectItBelongsTo() throws IOException {
        Path project = Files.createDirectories(dir.resolve("trip-planner"));
        Path venv = venv(project, ".venv");
        assertTrue(Environments.describe(venv).startsWith("trip-planner  ("),
                Environments.describe(venv));
    }

    @Test
    void linkingNeedsSomewhereRealToPointAt() throws IOException {
        Path project = Files.createDirectories(dir.resolve("five"));
        Optional<String> wrong = Environments.link(project, dir.resolve("not-an-environment"));
        assertTrue(wrong.isPresent());
        assertTrue(wrong.get().contains("not a Python environment"), wrong.get());
        assertFalse(Files.exists(project.resolve(".venv")));
    }

    @Test
    void linkingDoesNotWriteOverAnEnvironmentThatIsAlreadyThere() throws IOException {
        Path mine = Files.createDirectories(dir.resolve("six"));
        Path existing = venv(mine, ".venv");
        Path other = Files.createDirectories(dir.resolve("seven"));
        Path theirs = venv(other, ".venv");
        Optional<String> wrong = Environments.link(mine, theirs);
        assertTrue(wrong.isPresent());
        assertTrue(wrong.get().contains("already"), wrong.get());
        // And the one that was there is untouched.
        assertTrue(Environments.interpreterIn(existing).isPresent());
    }

    @Test
    void aLinkedEnvironmentIsFoundAsIfItWereTheProjectsOwn() throws IOException {
        Path owner = Files.createDirectories(dir.resolve("owner"));
        Path shared = venv(owner, ".venv");
        Path borrower = Files.createDirectories(dir.resolve("borrower"));

        Optional<String> wrong = Environments.link(borrower, shared);
        org.junit.jupiter.api.Assumptions.assumeTrue(wrong.isEmpty(),
                "linking is not available here: " + wrong.orElse(""));

        /* The point of the whole exercise: everything in the IDE looks for an interpreter
           inside the project, and finds this one without knowing it is somewhere else. */
        assertTrue(com.smide.plugins.python.PythonToolchain.venvInterpreter(borrower).isPresent());
        assertEquals(List.of(borrower.resolve(".venv")), Environments.venvsIn(List.of(borrower)));
    }
}
