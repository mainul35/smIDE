package com.smide.plugins.python;

import com.smide.api.problems.Diagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a Python project asks for, and whether its environment has it.
 *
 * <p>A requirement that is not installed in the project's virtual environment is reported on its
 * name: a misspelt package, or one added to the file and never installed.
 *
 * <p>Only when the project has an environment of its own - {@code .venv} or {@code venv} beside
 * the file. Without one, what is installed is somewhere this cannot see and has no business
 * guessing at: a machine's own Python, a conda environment, whatever the reader uses. Nothing is
 * said rather than something wrong.
 */
public final class PythonDependencies {

    public static final String SOURCE = "python";

    /**
     * A requirements line: the name, before any version, marker or comment.
     *
     * <p>Every part of it is written to stop at the end of the line. A character class excludes
     * what is named and nothing else - a newline is not named, so {@code [^;#]*} runs happily
     * from the first line's version into the rest of the file, and the whole file reads as one
     * requirement.
     */
    private static final Pattern REQUIREMENT = Pattern.compile(
            "(?m)^[ \\t]*([A-Za-z0-9][A-Za-z0-9._\\-]*)[ \\t]*(?:\\[[^]\\r\\n]*])?[ \\t]*"
                    + "(?:[=<>!~]=?[^;#\\r\\n]*)?[ \\t]*(?:;[^\\r\\n]*)?$");
    /** pyproject's list of them: {@code dependencies = ["httpx>=0.27", ...]}. */
    private static final Pattern PYPROJECT_LIST = Pattern.compile("(?ms)^\\s*dependencies\\s*=\\s*\\[(.*?)]");
    private static final Pattern IN_LIST = Pattern.compile("\"([^\"]+)\"|'([^']+)'");

    private PythonDependencies() {
    }

    public static boolean isManifest(Path file) {
        String name = file == null || file.getFileName() == null ? "" : file.getFileName().toString();
        return name.equals("requirements.txt") || name.equals("pyproject.toml");
    }

    public static List<Diagnostic> problemsIn(Path file, String text) {
        Path parent = file.toAbsolutePath().getParent();
        Path packages = parent == null ? null : sitePackages(parent);
        if (packages == null) {
            return List.of();
        }
        List<Diagnostic> out = new ArrayList<>();
        for (int[] span : requirements(file, text)) {
            String name = text.substring(span[0], span[1]);
            if (installed(packages, name)) {
                continue;
            }
            int[] start = lineColumn(text, span[0]);
            int[] end = lineColumn(text, span[1]);
            out.add(new Diagnostic(file, start[0], start[1], end[0], end[1], Diagnostic.Severity.ERROR,
                    "Cannot resolve " + name + ": it is not installed in this project's environment."
                            + " Install it to use it; if the install has run, the name is wrong.",
                    SOURCE, Diagnostic.UNRESOLVED));
        }
        return out;
    }

    /** Where each requirement's name is written. */
    static List<int[]> requirements(Path file, String text) {
        List<int[]> out = new ArrayList<>();
        if (file.getFileName().toString().equals("requirements.txt")) {
            Matcher line = REQUIREMENT.matcher(text);
            while (line.find()) {
                out.add(new int[] {line.start(1), line.end(1)});
            }
            return out;
        }
        Matcher list = PYPROJECT_LIST.matcher(text);
        while (list.find()) {
            Matcher item = IN_LIST.matcher(text.subSequence(list.start(1), list.end(1)));
            while (item.find()) {
                String written = item.group(1) != null ? item.group(1) : item.group(2);
                int at = list.start(1) + (item.group(1) != null ? item.start(1) : item.start(2));
                int length = nameLength(written);
                if (length > 0) {
                    out.add(new int[] {at, at + length});
                }
            }
        }
        return out;
    }

    /** How much of a requirement is its name: up to the version, the extras or the marker. */
    private static int nameLength(String requirement) {
        for (int i = 0; i < requirement.length(); i++) {
            char c = requirement.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '-' && c != '_') {
                return i;
            }
        }
        return requirement.length();
    }

    /**
     * Whether the environment has a package of this name.
     *
     * <p>Names are compared as the packaging tools compare them: case does not matter, and a
     * dash, an underscore and a dot are the same character. {@code Flask-SQLAlchemy} on disk is
     * the {@code flask_sqlalchemy} a file asks for.
     */
    private static boolean installed(Path packages, String name) {
        String wanted = normalized(name);
        try (Stream<Path> entries = Files.list(packages)) {
            for (Path entry : entries.toList()) {
                String found = entry.getFileName().toString();
                int dash = found.indexOf('-');
                String candidate = found.endsWith(".dist-info") || found.endsWith(".egg-info")
                        ? (dash > 0 ? found.substring(0, dash) : found)
                        : found.endsWith(".py") ? found.substring(0, found.length() - 3) : found;
                if (normalized(candidate).equals(wanted)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable: say nothing rather than something wrong.
            return true;
        }
        return false;
    }

    private static String normalized(String name) {
        return name.toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    /** The project's own site-packages, or null when it has no environment of its own. */
    static Path sitePackages(Path projectDir) {
        for (String venv : List.of(".venv", "venv", ".env")) {
            Path dir = projectDir.resolve(venv);
            Path windows = dir.resolve("Lib").resolve("site-packages");
            if (Files.isDirectory(windows)) {
                return windows;
            }
            Path lib = dir.resolve("lib");
            if (!Files.isDirectory(lib)) {
                continue;
            }
            try (Stream<Path> versions = Files.list(lib)) {
                for (Path version : versions.toList()) {
                    Path packages = version.resolve("site-packages");
                    if (Files.isDirectory(packages)) {
                        return packages;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Try the next place it might be.
            }
        }
        return null;
    }

    private static int[] lineColumn(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, offset - lineStart};
    }
}
