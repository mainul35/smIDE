package com.smide.plugins.python;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A Python interpreter: the project's own virtual environment first, then one installed on the machine. */
public final class PythonToolchain implements Toolchain {

    public static final String HOME_SETTING = "python.home";
    public static final String DOWNLOAD = "https://www.python.org/downloads/";

    @Override
    public String id() {
        return "python";
    }

    @Override
    public String displayName() {
        return "Python";
    }

    @Override
    public String purpose() {
        return "Running Python scripts, modules and tests needs an interpreter. A project's own .venv is used when it has one.";
    }

    @Override
    public String downloadUrl() {
        return DOWNLOAD;
    }

    @Override
    public String homeSetting() {
        return HOME_SETTING;
    }

    /** A Python project - unless it carries its own interpreter in a virtual environment. */
    @Override
    public boolean isNeededBy(Path root) {
        if (venvInterpreter(root).isPresent()) {
            return false;
        }
        for (String marker : List.of("pyproject.toml", "requirements.txt", "setup.py", "Pipfile")) {
            if (Files.isRegularFile(root.resolve(marker))) {
                return true;
            }
        }
        return ProjectFiles.any(root, 3, p -> ProjectFiles.hasExtension(p, "py"));
    }

    /**
     * An interpreter installed on the machine.
     *
     * <p>Windows's own python.exe alias is passed over: until Python is installed from the
     * Store it opens the Store instead of running anything.
     */
    @Override
    public Optional<Path> locate(Ide ide) {
        String program = Executables.WINDOWS ? "python" : "python3";
        Optional<Path> found = Executables.find(ide, HOME_SETTING, program, usual(), p -> !Executables.isStoreAlias(p));
        if (found.isPresent()) {
            return found;
        }
        if (!Executables.WINDOWS) {
            return Executables.find(ide, null, "python", List.of(), p -> true);
        }
        // python.org's installer adds the py launcher, which runs the newest Python it knows.
        return Executables.onPath("py", p -> !Executables.isStoreAlias(p));
    }

    @Override
    public boolean accepts(Path home) {
        return Executables.in(home, Executables.WINDOWS ? "python" : "python3").isPresent()
                || Executables.in(home, "python").isPresent();
    }

    /** The interpreter of the project's virtual environment, if it has one. */
    public static Optional<Path> venvInterpreter(Path root) {
        for (String name : List.of(".venv", "venv", "env")) {
            Path interpreter = Executables.WINDOWS
                    ? root.resolve(name).resolve("Scripts").resolve("python.exe")
                    : root.resolve(name).resolve("bin").resolve("python");
            if (Files.isRegularFile(interpreter)) {
                return Optional.of(interpreter);
            }
        }
        return Optional.empty();
    }

    private static List<Path> usual() {
        List<Path> folders = new ArrayList<>();
        if (Executables.WINDOWS) {
            Path local = Executables.env("LOCALAPPDATA");
            if (local != null) {
                folders.addAll(Executables.subfolders(local.resolve("Programs").resolve("Python"), "Python3"));
            }
            folders.addAll(Executables.subfolders(Path.of("C:\\"), "Python3"));
            Path programFiles = Executables.env("ProgramFiles");
            if (programFiles != null) {
                folders.addAll(Executables.subfolders(programFiles, "Python3"));
            }
        } else {
            folders.add(Path.of("/usr/bin"));
            folders.add(Path.of("/usr/local/bin"));
            folders.add(Path.of("/opt/homebrew/bin"));
            folders.add(Executables.home().resolve(".pyenv").resolve("shims"));
        }
        return folders;
    }
}
