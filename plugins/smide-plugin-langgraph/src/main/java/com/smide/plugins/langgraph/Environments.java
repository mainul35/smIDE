package com.smide.plugins.langgraph;

import com.smide.api.Ide;
import com.smide.api.util.Executables;
import com.smide.plugins.python.PythonToolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finding a Python environment that already exists, and pointing a new project at it.
 *
 * <p>Making a fresh environment per project is the tidy thing to do, and on a machine with
 * Smart App Control enforcing it is also the slow thing. Windows decides whether to allow a
 * compiled extension per file, not per content: two byte-identical copies of the same wheel
 * get separate verdicts, and a copy of a file that has been cleared starts the waiting over.
 * Measured here - the same {@code ormsgpack.cp311-win_amd64.pyd}, same SHA-256, allowed in an
 * environment three hours old and refused in one built forty minutes earlier, and refused
 * again in a copy made a minute earlier. So a new environment means an hour in which the
 * project cannot run, every time, and copying a good one does not help.
 *
 * <p>What does help is not making new files at all. An environment that has already been
 * cleared goes on working wherever it is used from, so a project that borrows one runs the
 * moment it is created. The borrowing is a directory link at {@code .venv}, because that is
 * where everything in the IDE already looks - the toolchain, the kernel, the language server,
 * the run configurations - and a link is followed by all of them without any of them knowing.
 *
 * <p>The cost of borrowing is that the environment is shared: a package installed from one
 * project appears in every project linked to it. That is a real tradeoff and the dialog says
 * so, rather than being quietly arranged behind the user's back.
 */
final class Environments {

    private Environments() {
    }

    /** Where a venv is kept inside a project, in the order {@link PythonToolchain} looks. */
    static final List<String> NAMES = List.of(".venv", "venv", "env");

    /**
     * Environments worth offering, newest-looking first.
     *
     * <p>Only projects the IDE already knows about: the open ones and the ones recently
     * worked in. Searching the disk for environments would find dozens that belong to other
     * work and mean nothing here.
     */
    static List<Path> reusable(Ide ide) {
        Set<Path> roots = new LinkedHashSet<>();
        ide.workspaces().all().forEach(w -> roots.add(w.root()));
        for (Path file : ide.editors().recentFiles()) {
            ide.workspaces().containing(file).ifPresent(w -> roots.add(w.root()));
            if (file.getParent() != null) {
                roots.add(file.getParent());
            }
        }
        return venvsIn(new ArrayList<>(roots));
    }

    /**
     * The environment directories under these roots.
     *
     * <p>Takes the roots rather than finding them, so that what it does can be tested without
     * an IDE to ask.
     */
    static List<Path> venvsIn(List<Path> roots) {
        List<Path> found = new ArrayList<>();
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            for (String name : NAMES) {
                Path venv = root.resolve(name);
                if (interpreterIn(venv).isPresent() && !found.contains(venv)) {
                    found.add(venv);
                }
            }
        }
        return List.copyOf(found);
    }

    /** The interpreter inside an environment directory, if that is what it is. */
    static Optional<Path> interpreterIn(Path venv) {
        if (venv == null) {
            return Optional.empty();
        }
        Path interpreter = Executables.WINDOWS
                ? venv.resolve("Scripts").resolve("python.exe")
                : venv.resolve("bin").resolve("python");
        return Files.isRegularFile(interpreter) ? Optional.of(interpreter) : Optional.empty();
    }

    /** Whether an environment has what a LangGraph project needs, and will let it load. */
    static boolean carriesLangGraph(Path venv) {
        Optional<Path> interpreter = interpreterIn(venv);
        if (interpreter.isEmpty()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(interpreter.get().toString(),
                    "-c", "import langgraph.graph").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Points {@code project/.venv} at an environment that already exists.
     *
     * <p>A junction on Windows and a symbolic link elsewhere. A junction rather than a
     * symbolic link on Windows for one reason: making a symbolic link there needs either
     * administrator rights or Developer Mode, and a junction needs neither, which is the
     * difference between a feature that works for everybody and one that works for people who
     * have already changed a Windows setting. Java cannot make a junction, so {@code mklink}
     * does it - through {@code cmd}, which is signed and starts.
     *
     * @return empty when it worked, or what went wrong
     */
    static Optional<String> link(Path project, Path environment) {
        Path at = project.resolve(".venv");
        if (Files.exists(at)) {
            return Optional.of("there is already a .venv here");
        }
        if (interpreterIn(environment).isEmpty()) {
            return Optional.of(environment + " is not a Python environment");
        }
        try {
            if (!Executables.WINDOWS) {
                Files.createSymbolicLink(at, environment);
                return Optional.empty();
            }
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    at.toString(), environment.toString()).redirectErrorStream(true).start();
            String said = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.of("linking did not finish");
            }
            if (process.exitValue() != 0) {
                return Optional.of(said.isBlank() ? "could not link .venv" : said.strip());
            }
            return interpreterIn(at).isPresent()
                    ? Optional.empty()
                    : Optional.of("the link was made but has no interpreter in it");
        } catch (IOException | UnsupportedOperationException e) {
            return Optional.of(String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted");
        }
    }

    /** How an environment is shown in a list: the project it belongs to, then where it is. */
    static String describe(Path venv) {
        Path project = venv.getParent();
        String owner = project == null || project.getFileName() == null
                ? venv.toString() : project.getFileName().toString();
        return owner + "  (" + venv + ")";
    }
}
