package com.smide.api.util;

import com.smide.api.Ide;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Finding a program on this machine: from its setting, the PATH, or where installers put it.
 *
 * <p>The PATH alone is not enough, and every language plugin learnt that separately. An IDE
 * started from a desktop menu has a shorter PATH than a terminal - cargo, go and dotnet
 * installed, working in a shell, and invisible to the application - so the usual install
 * folders are looked in as well.
 */
public final class Executables {

    public static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private Executables() {
    }

    /** The file names a program goes by: go.exe, npm.cmd or run.bat on Windows; just go elsewhere. */
    public static List<String> names(String program) {
        return WINDOWS ? List.of(program + ".exe", program + ".cmd", program + ".bat") : List.of(program);
    }

    /** A program in a folder's bin, or in the folder itself. */
    public static Optional<Path> in(Path folder, String program) {
        if (folder == null) {
            return Optional.empty();
        }
        for (Path dir : List.of(folder.resolve("bin"), folder)) {
            for (String name : names(program)) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** A program on the PATH, passing over any the filter refuses. */
    public static Optional<Path> onPath(String program, Predicate<Path> accept) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names(program)) {
                try {
                    Path candidate = Path.of(dir.strip()).resolve(name);
                    if (Files.isRegularFile(candidate) && accept.test(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (RuntimeException ignored) {
                    // A malformed PATH entry.
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<Path> onPath(String program) {
        return onPath(program, p -> true);
    }

    /**
     * A program from its setting, then the PATH, then the folders installers use.
     *
     * @param setting a setting holding the install folder or the program itself, or null for none
     * @param usual   install folders to look in, each checked itself and in its bin
     * @param accept  refuses a candidate that is not the real thing - a Store alias, a shim
     */
    public static Optional<Path> find(Ide ide, String setting, String program, List<Path> usual, Predicate<Path> accept) {
        if (setting != null && ide != null) {
            String value = ide.settings().get(setting, "");
            if (!value.isBlank()) {
                Path configured = Path.of(value.strip());
                if (Files.isRegularFile(configured)) {
                    return Optional.of(configured);
                }
                Optional<Path> inside = in(configured, program);
                if (inside.isPresent()) {
                    return inside;
                }
            }
        }
        Optional<Path> onPath = onPath(program, accept);
        if (onPath.isPresent()) {
            return onPath;
        }
        for (Path folder : usual) {
            Optional<Path> found = in(folder, program).filter(accept);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * One of Windows's app execution aliases.
     *
     * <p>For some programs - python.exe above all - the alias installed with Windows does not
     * run anything: it opens the Microsoft Store. Taking it for an interpreter gets a run that
     * prints nothing and a Store window.
     */
    public static boolean isStoreAlias(Path path) {
        return path.toString().replace('/', '\\').toLowerCase(Locale.ROOT).contains("\\microsoft\\windowsapps\\");
    }

    /** Subfolders whose names start with a prefix, newest-looking first: Python312 before Python311. */
    public static List<Path> subfolders(Path parent, String prefix) {
        List<Path> out = new ArrayList<>();
        if (parent == null || !Files.isDirectory(parent)) {
            return out;
        }
        try (Stream<Path> children = Files.list(parent)) {
            children.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .forEach(out::add);
        } catch (IOException | RuntimeException ignored) {
            // Nothing readable there.
        }
        return out;
    }

    /** An environment variable as a path, or null when it is not set. */
    public static Path env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Path.of(value.strip());
    }

    /** The user's home folder. */
    public static Path home() {
        return Path.of(System.getProperty("user.home", "."));
    }

    /** Folders under Program Files and Program Files (x86), for the names given; empty elsewhere. */
    public static List<Path> programFiles(String... names) {
        List<Path> out = new ArrayList<>();
        if (!WINDOWS) {
            return out;
        }
        for (String variable : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            Path base = env(variable);
            if (base == null) {
                continue;
            }
            for (String name : names) {
                out.add(variable.equals("LOCALAPPDATA") ? base.resolve("Programs").resolve(name) : base.resolve(name));
            }
        }
        return out;
    }
}
