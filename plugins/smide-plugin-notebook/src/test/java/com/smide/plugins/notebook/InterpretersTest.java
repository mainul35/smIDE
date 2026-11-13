package com.smide.plugins.notebook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding the Python a notebook should run in.
 *
 * <p>The venv lookup itself belongs to the Python plugin and is tested there; what is
 * tested here is the walk, which is where the bug was: asking only the workspace root
 * found nothing in a workspace that had been adopted from a single opened file, and the
 * notebook silently ran in the machine's Python instead of the project's.
 */
class InterpretersTest {

    /** Stands in for the Python plugin's venvInterpreter: a folder with a marker file. */
    private static Optional<Path> venvAt(Path folder) {
        Path marker = folder.resolve(".venv").resolve("python");
        return Files.isRegularFile(marker) ? Optional.of(marker) : Optional.empty();
    }

    private static Path makeVenv(Path folder) throws Exception {
        Path marker = folder.resolve(".venv").resolve("python");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "");
        return marker;
    }

    private static final java.util.function.Supplier<Optional<Path>> SYSTEM =
            () -> Optional.of(Path.of("C:\\Python311\\python.exe"));

    @Test
    void takesTheEnvironmentBesideTheNotebook(@TempDir Path project) throws Exception {
        Path venv = makeVenv(project);
        Path notebook = project.resolve("work.ipynb");

        assertEquals(venv, Interpreters.forNotebook(notebook, project,
                InterpretersTest::venvAt, SYSTEM).orElseThrow());
    }

    @Test
    void looksUpwardsFromTheNotebook(@TempDir Path project) throws Exception {
        Path venv = makeVenv(project);
        Path notebook = project.resolve("notebooks").resolve("deep").resolve("work.ipynb");
        Files.createDirectories(notebook.getParent());

        assertEquals(venv, Interpreters.forNotebook(notebook, project,
                InterpretersTest::venvAt, SYSTEM).orElseThrow());
    }

    /** The bug: the workspace was a folder far above the project, so nothing was found. */
    @Test
    void findsTheProjectsEnvironmentEvenWhenTheWorkspaceIsAFolderAboveIt(@TempDir Path home)
            throws Exception {
        Path project = home.resolve("codes").resolve("myproject");
        Files.createDirectories(project);
        Path venv = makeVenv(project);
        Path notebook = project.resolve("work.ipynb");

        assertEquals(venv, Interpreters.forNotebook(notebook, home,
                InterpretersTest::venvAt, SYSTEM).orElseThrow());
    }

    @Test
    void theNearestEnvironmentWinsOverOneFurtherUp(@TempDir Path outer) throws Exception {
        makeVenv(outer);
        Path inner = outer.resolve("inner");
        Files.createDirectories(inner);
        Path nearest = makeVenv(inner);

        assertEquals(nearest, Interpreters.forNotebook(inner.resolve("work.ipynb"), outer,
                InterpretersTest::venvAt, SYSTEM).orElseThrow());
    }

    @Test
    void fallsBackToTheMachinesPythonWhenThereIsNoEnvironment(@TempDir Path project) {
        assertEquals(Path.of("C:\\Python311\\python.exe"),
                Interpreters.forNotebook(project.resolve("work.ipynb"), project,
                        InterpretersTest::venvAt, SYSTEM).orElseThrow());
    }

    @Test
    void findsNothingWhenThereIsNothingToFind(@TempDir Path project) {
        assertTrue(Interpreters.forNotebook(project.resolve("work.ipynb"), project,
                InterpretersTest::venvAt, Optional::empty).isEmpty());
    }

    // ---------------------------------------------------------------- the walk itself

    @Test
    void theWalkStopsAtTheWorkspaceRoot() {
        List<Path> folders = Interpreters.folders(
                Path.of("C:\\p\\a\\b\\work.ipynb"), Path.of("C:\\p"));

        assertEquals(List.of(Path.of("C:\\p\\a\\b"), Path.of("C:\\p\\a"), Path.of("C:\\p")),
                folders);
    }

    @Test
    void theWalkDoesNotWanderToTheDriveWhenTheRootIsElsewhere() {
        List<Path> folders = Interpreters.folders(
                Path.of("C:\\a\\b\\c\\d\\e\\f\\g\\work.ipynb"), Path.of("D:\\other"));

        assertEquals(Interpreters.LEVELS + 1, folders.size(), "six levels, then the root");
        assertEquals(Path.of("C:\\a\\b\\c\\d\\e\\f\\g"), folders.get(0));
        assertEquals(Path.of("D:\\other"), folders.get(folders.size() - 1));
    }

    @Test
    void aRootThatIsNotAnAncestorIsStillAsked() {
        List<Path> folders = Interpreters.folders(
                Path.of("C:\\p\\notebooks\\work.ipynb"), Path.of("C:\\p\\src"));

        assertTrue(folders.contains(Path.of("C:\\p\\src")));
        assertEquals(Path.of("C:\\p\\notebooks"), folders.get(0));
    }

    @Test
    void nothingToWalkIsAnEmptyWalk() {
        assertTrue(Interpreters.folders(null, null).isEmpty());
    }
}
