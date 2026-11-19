package com.smide.api.execution;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        return runnable(command, ownPath());
    }

    /**
     * The same, looking on a PATH that is not this process's own.
     *
     * <p>Which matters because the IDE's PATH is not the developer's. A profile read by their
     * shell adds to theirs and not to ours, so a command about to be started with that shell's
     * environment has to be looked up on that shell's PATH as well: resolving the name against
     * one PATH and running it with another is how a command is found and then not found.
     *
     * @param path a PATH as the platform writes one, or null for this process's own
     */
    public static List<String> runnable(List<String> command, String path) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        Path found = find(command.get(0), path);
        if (found == null) {
            return command;
        }
        List<String> out = new ArrayList<>(command);
        out.set(0, found.toString());
        return out;
    }

    /**
     * The PATH held in an environment, whatever case it is spelled in.
     *
     * <p>Windows environment names are case-insensitive and are handed out in whatever case they
     * were set: {@code Path} from the registry, {@code PATH} from a shell that inherited it from
     * something Unix-shaped. A map is neither, so the lookup has to be.
     */
    public static String pathIn(Map<String, String> environment) {
        if (environment == null) {
            return ownPath();
        }
        for (Map.Entry<String, String> each : environment.entrySet()) {
            if ("PATH".equalsIgnoreCase(each.getKey()) && each.getValue() != null) {
                return each.getValue();
            }
        }
        return ownPath();
    }

    private static String ownPath() {
        return System.getenv().getOrDefault("PATH", "");
    }

    /**
     * The file a bare command name resolves to on this machine, or null.
     *
     * <p>Null too for a name that needs no help - one with a slash in it, or one that already ends
     * in an extension Windows knows - because those are started as they are.
     */
    public static Path find(String command) {
        return find(command, ownPath());
    }

    /** The same, on a PATH that is not this process's own. */
    public static Path find(String command, String path) {
        if (command == null || command.isBlank() || !windows()) {
            return null;
        }
        if (command.contains("/") || command.contains("\\") || hasExtension(command)) {
            return null;
        }
        for (String folder : (path == null ? ownPath() : path).split(File.pathSeparator)) {
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
        return canRun(command, ownPath());
    }

    /**
     * The same, on a PATH that is not this process's own.
     *
     * <p>This is the question the assistant got wrong in front of a developer: it asked whether
     * {@code mvn} could be run, was answered from the IDE's PATH rather than from the shell's,
     * and reported that the machine had no Maven on it while a terminal tab below printed the
     * version. Ask about the PATH the command will actually be started with.
     */
    public static boolean canRun(String command, String path) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (command.contains("/") || command.contains("\\")) {
            return Files.isRegularFile(Path.of(command));
        }
        if (windows()) {
            return hasExtension(command) ? onPathAs(command, "", path) : find(command, path) != null;
        }
        return onPathAs(command, "", path);
    }

    private static boolean onPathAs(String command, String extension, String path) {
        for (String folder : (path == null ? ownPath() : path).split(File.pathSeparator)) {
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
