package com.smide.plugins.rust;

import com.smide.api.problems.Diagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a Cargo.toml asks for, and whether Cargo has it.
 *
 * <p>A crate named in a dependency table that is nowhere in the registry Cargo has downloaded is
 * reported on its name - which is how a misspelt crate is caught while it is being typed rather
 * than by a build.
 *
 * <p>Only when the registry exists, and only for crates that come from it: a path or a git
 * dependency is somewhere else by definition, and a machine where Cargo has never fetched
 * anything would have every line red for no reason of the file's own.
 */
public final class CargoDependencies {

    public static final String SOURCE = "cargo";

    /** The tables whose keys are crate names. */
    private static final Pattern TABLE = Pattern.compile(
            "(?m)^\\s*\\[(?:[A-Za-z0-9_.\\-]+\\.)?(dependencies|dev-dependencies|build-dependencies)]\\s*$");
    /** A table heading of any kind, which is where the one above ends. */
    private static final Pattern ANY_TABLE = Pattern.compile("(?m)^\\s*\\[");
    /** {@code name = "1.0"} or {@code name = { version = "1.0" }}, at the start of a line. */
    private static final Pattern ENTRY = Pattern.compile("(?m)^\\s*([A-Za-z0-9_\\-]+)\\s*=\\s*(\"[^\"]*\"|\\{[^}]*})");

    private CargoDependencies() {
    }

    public static boolean isManifest(Path file) {
        return file != null && file.getFileName() != null && file.getFileName().toString().equals("Cargo.toml");
    }

    public static List<Diagnostic> problemsIn(Path file, String text) {
        return problemsIn(file, text, registry());
    }

    /** The same, against a registry named outright, which is how this is tested. */
    static List<Diagnostic> problemsIn(Path file, String text, Path registry) {
        if (registry == null) {
            return List.of();
        }
        List<Diagnostic> out = new ArrayList<>();
        Matcher table = TABLE.matcher(text);
        while (table.find()) {
            int from = table.end();
            Matcher next = ANY_TABLE.matcher(text);
            int to = next.find(from) ? next.start() : text.length();
            Matcher entry = ENTRY.matcher(text.subSequence(from, to));
            while (entry.find()) {
                String name = entry.group(1);
                if (elsewhere(entry.group(2)) || downloaded(registry, name)) {
                    continue;
                }
                int start = from + entry.start(1);
                int end = from + entry.end(1);
                int[] at = lineColumn(text, start);
                int[] until = lineColumn(text, end);
                out.add(new Diagnostic(file, at[0], at[1], until[0], until[1], Diagnostic.Severity.ERROR,
                        "Cannot resolve the crate " + name + ": Cargo has not got it. It is fetched on the"
                                + " next build; if it never arrives, the name is wrong.",
                        SOURCE, Diagnostic.UNRESOLVED));
            }
        }
        return out;
    }

    /** A dependency that does not come from the registry: a folder of this project, or a repository. */
    private static boolean elsewhere(String value) {
        return value.contains("path") || value.contains("git") || value.contains("workspace");
    }

    /** Whether a crate of this name has been unpacked or cached, at any version. */
    private static boolean downloaded(Path registry, String name) {
        for (String where : List.of("src", "cache")) {
            Path dir = registry.resolve(where);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> registries = Files.list(dir)) {
                for (Path one : registries.toList()) {
                    try (Stream<Path> crates = Files.list(one)) {
                        if (crates.anyMatch(p -> isCrate(p.getFileName().toString(), name))) {
                            return true;
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Unreadable; treat it as nothing found here and try the next place.
            }
        }
        return false;
    }

    /** {@code serde-1.0.197} and {@code serde-1.0.197.crate} are both the crate {@code serde}. */
    private static boolean isCrate(String fileName, String name) {
        String prefix = name + "-";
        return fileName.startsWith(prefix)
                && fileName.length() > prefix.length()
                && Character.isDigit(fileName.charAt(prefix.length()));
    }

    /** Where Cargo keeps what it has downloaded, or null when it has never downloaded anything. */
    static Path registry() {
        String configured = System.getenv("CARGO_HOME");
        Path home = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home", "."), ".cargo");
        Path registry = home.resolve("registry");
        return Files.isDirectory(registry) ? registry : null;
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
