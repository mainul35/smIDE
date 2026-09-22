package com.smide.api.execution;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finding the file that a command name means.
 *
 * <p>On Unix this is nothing: the name is the file, the kernel finds it on the PATH, and a shell
 * script runs because its first line says how. On Windows it is a trap. The thing on the PATH
 * called {@code mvn} is a shell script for Git Bash, and the one Windows can actually start is
 * {@code mvn.cmd} beside it - but {@code CreateProcess}, which is what every Java program ends up
 * calling, appends {@code .exe} and nothing else. So {@code mvn} fails with "the system cannot
 * find the file specified" on a machine where Maven is plainly installed and {@code mvn} works
 * perfectly in a terminal, because the terminal is cmd.exe and cmd.exe knows about PATHEXT.
 *
 * <p>This is that lookup, done before the process is started: the name is turned into the path of
 * the file Windows will run, in the order PATHEXT gives, and left alone everywhere else.
 */
public final class Executables {

    /** What Windows uses when PATHEXT is not set, and the order it tries them in. */
    private static final String DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    private Executables() {
    }

    /**
     * The command with its first word resolved to a file Windows can start.
     *
     * <p>Unchanged when the name already has a path or an extension, when nothing matching is
     * found, or when this is not Windows: a command that cannot be helped is left to fail with
     * its own message rather than quietly turned into something else.
     */
    public static List<String> runnable(List<String> command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        Path found = find(command.get(0));
        if (found == null) {
            return command;
        }
        List<String> out = new ArrayList<>(command);
        out.set(0, found.toString());
        return out;
    }

    /**
     * The file a bare command name resolves to on this machine, or null.
     *
     * <p>Null too for a name that needs no help - one with a slash in it, or one that already ends
     * in an extension Windows knows - because those are started as they are.
     */
    public static Path find(String command) {
        if (command == null || command.isBlank() || !windows()) {
            return null;
        }
        if (command.contains("/") || command.contains("\\") || hasExtension(command)) {
            return null;
        }
        for (String folder : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (folder.isBlank()) {
                continue;
            }
            Path where = folderOf(folder);
            if (where == null) {
                continue;
            }
            for (String extension : extensions()) {
                Path candidate = where.resolve(command + extension);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Whether this command can be started at all: as it stands, or once resolved.
     *
     * <p>Strict about extensions on Windows, deliberately. A bare {@code mvn} sitting on the PATH
     * is not something Windows can start, however much it looks like one, and saying otherwise
     * only moves the failure to where it is harder to read.
     */
    public static boolean canRun(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (command.contains("/") || command.contains("\\")) {
            return Files.isRegularFile(Path.of(command));
        }
        if (windows()) {
            return hasExtension(command) ? onPathAs(command, "") : find(command) != null;
        }
        return onPathAs(command, "");
    }

    private static boolean onPathAs(String command, String extension) {
        for (String folder : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (folder.isBlank()) {
                continue;
            }
            Path where = folderOf(folder);
            if (where != null && Files.isRegularFile(where.resolve(command + extension))) {
                return true;
            }
        }
        return false;
    }

    private static Path folderOf(String folder) {
        try {
            // A PATH is a user's, and users put quotes and nonsense in theirs.
            return Path.of(folder.replace("\"", "").trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasExtension(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (String extension : extensions()) {
            if (lower.endsWith(extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> extensions() {
        String pathext = System.getenv().getOrDefault("PATHEXT", DEFAULT_PATHEXT);
        List<String> out = new ArrayList<>();
        for (String each : pathext.split(";")) {
            String trimmed = each.trim();
            if (!trimmed.isBlank()) {
                out.add(trimmed.startsWith(".") ? trimmed : "." + trimmed);
            }
        }
        return out.isEmpty() ? List.of(DEFAULT_PATHEXT.split(";")) : out;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
