package com.smide.api.execution;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The environment the developer's own shell has, which is not the one the IDE was started with.
 *
 * <p>These are two different environments and nothing keeps them in step. A process inherits the
 * environment of whatever started it, and the IDE is started from a desktop shortcut, a dock icon
 * or a launcher - things that hand it the environment the desktop session was created with, often
 * months ago. The shell in the Terminal tool window then does what shells do: it reads the
 * profile. {@code .bashrc}, {@code .zprofile}, a PowerShell {@code $PROFILE}, an SDK manager's
 * shim, a version manager's hook - all of them add to the PATH, and all of them run in the
 * terminal and in no other part of the IDE.
 *
 * <p>So the Terminal has Maven and the rest of the IDE does not, on the same machine, at the same
 * moment. That is not a theory: the assistant was asked to build a project, looked for {@code
 * java} and {@code mvn} on the PATH it was handed, found neither, and told the developer there was
 * no toolchain reachable at all - while a terminal tab two inches below it answered {@code mvn -v}
 * with the version and the Maven home. From where the assistant stood that answer was true. It was
 * reading the wrong environment.
 *
 * <p>This asks the shell. Once, the first time anything needs to know, by starting the same shell
 * the Terminal starts - profile and all - and having it print its environment. What comes back is
 * remembered for the life of the IDE, because a profile is read at login and does not change under
 * anybody, and because the cost of being wrong here is one process and the cost of asking every
 * time is one process per command.
 *
 * <p>When the shell cannot be asked - it is not there, it hangs on a prompt in somebody's profile,
 * it prints something unparseable - this gives back the IDE's own environment, which is exactly
 * where things stood before. Nothing here can make the situation worse than not asking.
 */
public final class Shell {

    /** Settings key holding the developer's shell; empty means "the platform default". */
    public static final String SHELL_SETTING = "terminal.shell";

    /**
     * How long a profile is given to finish.
     *
     * <p>Generous on purpose. A profile that sources a version manager and resolves a toolchain
     * can take seconds on a cold start, and the answer is worth waiting for once. A profile that
     * stops to ask a question would wait for ever, which is what the limit is really for.
     */
    private static final long SECONDS = 20;

    /** What PowerShell is asked to print: one NAME=VALUE per line, the same shape as env(1). */
    private static final String WINDOWS_DUMP =
            "Get-ChildItem Env: | ForEach-Object { $_.Name + '=' + $_.Value }";

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** What each shell answered, keyed by the shell that was asked. One question per shell. */
    private static final Map<String, Map<String, String>> ANSWERED = new ConcurrentHashMap<>();

    private Shell() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    /**
     * The command line that starts an interactive shell: the executable and its arguments.
     *
     * <p>Windows gets {@code pwsh.exe} when it is installed and {@code powershell.exe} when it is
     * not. macOS gets a login shell, which is the convention there and the only way {@code
     * /etc/paths} is read at all; Linux gets a plain interactive one, which is what its terminals
     * do and what its profiles expect.
     *
     * @param configured the {@link #SHELL_SETTING} value, which wins whenever it is set
     */
    public static String[] interactive(String configured) {
        String wanted = configured == null ? "" : configured.strip();
        if (!wanted.isEmpty()) {
            return split(wanted);
        }
        if (WINDOWS) {
            String pwsh = onPath("pwsh.exe");
            // -NoLogo keeps the banner out of a fresh tab; the profile still runs.
            return new String[]{pwsh != null ? pwsh : "powershell.exe", "-NoLogo"};
        }
        return MAC ? new String[]{unixShell(), "-l"} : new String[]{unixShell()};
    }

    /** The default shell's name, for a settings page to show as the prompt text. */
    public static String defaultDescription() {
        if (WINDOWS) {
            return onPath("pwsh.exe") != null ? "pwsh.exe" : "powershell.exe";
        }
        return unixShell();
    }

    /**
     * The command that runs one line in a shell that has read the profile.
     *
     * <p>Not interactive and not a login tab: one line, printed, gone. {@code -NonInteractive}
     * stops PowerShell prompting into a pipe nobody is typing at; it does not stop the profile
     * from running, which is the entire reason for going through a shell rather than starting the
     * program directly. {@code -lc} does the same job on Unix.
     */
    public static List<String> asking(String configured, String line) {
        List<String> command = new ArrayList<>(List.of(interactive(configured)));
        // The flags that only make sense for a tab somebody is typing into.
        command.removeAll(List.of("-NoLogo", "-l", "--login", "-i"));
        command.addAll(flagsFor(command.isEmpty() ? "" : command.get(0), line));
        return List.copyOf(command);
    }

    /**
     * How to hand one line to a shell, decided by which shell it is.
     *
     * <p>Three families, because the developer may have configured any of them. PowerShell takes
     * {@code -Command}; {@code cmd.exe} takes {@code /c} and has no profile to read, so going
     * through it buys nothing but costs nothing either; everything else is a Bourne shell and
     * takes {@code -lc}, the {@code l} being the part that reads the profile.
     */
    private static List<String> flagsFor(String executable, String line) {
        String name = nameOf(executable);
        if (name.startsWith("pwsh") || name.startsWith("powershell")) {
            return List.of("-NoLogo", "-NonInteractive", "-Command", line);
        }
        if (name.startsWith("cmd")) {
            return List.of("/c", line);
        }
        return List.of("-lc", line);
    }

    /** How to ask this shell for its environment, in its own language. */
    static String dumpFor(String executable) {
        String name = nameOf(executable);
        if (name.startsWith("pwsh") || name.startsWith("powershell")) {
            return WINDOWS_DUMP;
        }
        return name.startsWith("cmd") ? "set" : "env";
    }

    /** The bare file name of a command, however it was spelled. */
    private static String nameOf(String executable) {
        String name = executable.toLowerCase(Locale.ROOT).replace('\\', '/');
        return name.substring(name.lastIndexOf('/') + 1);
    }

    /**
     * The environment the shell has, asked once and remembered.
     *
     * <p>The IDE's own when the shell could not be asked, so a caller never has to tell the two
     * cases apart: what comes back is always an environment to run something in, and the only
     * difference is how good it is.
     */
    public static Map<String, String> environment(String configured) {
        String key = String.join(" ", interactive(configured));
        // The fallback is remembered too: a shell that could not be started will not start on the
        // next command either, and paying twenty seconds to find that out again, per command, is
        // worse than the problem being solved here.
        return ANSWERED.computeIfAbsent(key, ignored -> {
            Map<String, String> answer = ask(configured);
            return answer.isEmpty() ? Map.copyOf(System.getenv()) : answer;
        });
    }

    /** Just the PATH out of it, which is what almost every caller is really after. */
    public static String path(String configured) {
        return Executables.pathIn(environment(configured));
    }

    /** Forgets what the shell said, so the next question asks it again. */
    public static void forget() {
        ANSWERED.clear();
    }

    /** Starts the shell, has it print its environment, and reads what came back. */
    private static Map<String, String> ask(String configured) {
        List<String> command = asking(configured, dumpFor(interactive(configured)[0]));
        Process process = null;
        try {
            process = new ProcessBuilder(Executables.runnable(command))
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            // Read first, wait after: a profile that prints more than the pipe holds would
            // otherwise block for ever on a buffer nobody is emptying, and look like a hang.
            String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(SECONDS, TimeUnit.SECONDS)) {
                return Map.of();
            }
            return process.exitValue() == 0 ? parse(printed) : Map.of();
        } catch (IOException | RuntimeException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * What a shell printed, turned back into an environment.
     *
     * <p>A line is a variable when it begins with a name and an equals sign; anything else is the
     * second line of a value, or a profile saying good morning, and is dropped rather than guessed
     * at. That loses the tail of a multi-line variable, which is a trade worth making: nothing
     * this is wanted for is multi-line, and a wrong PATH is worse than a short one. Windows names
     * such as {@code ProgramFiles(x86)} carry brackets, so brackets are part of a name here.
     */
    static Map<String, String> parse(String printed) {
        Map<String, String> found = new LinkedHashMap<>();
        if (printed == null) {
            return found;
        }
        for (String line : printed.split("\r?\n")) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = line.substring(0, equals);
            if (isName(name)) {
                found.put(name, line.substring(equals + 1));
            }
        }
        return found;
    }

    private static boolean isName(String name) {
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            return false;
        }
        for (int at = 0; at < name.length(); at++) {
            char c = name.charAt(at);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '(' && c != ')') {
                return false;
            }
        }
        return true;
    }

    private static String unixShell() {
        String shell = System.getenv("SHELL");
        return shell == null || shell.isBlank() ? "/bin/bash" : shell;
    }

    /**
     * Splits a configured shell line into executable and arguments.
     *
     * <p>A path that exists as a whole - spaces included, as in {@code C:\Program
     * Files\PowerShell\7\pwsh.exe} - is kept in one piece; otherwise the line is split on
     * whitespace, honouring double quotes.
     */
    public static String[] split(String line) {
        try {
            if (Files.isRegularFile(Path.of(line))) {
                return new String[]{line};
            }
        } catch (RuntimeException ignored) {
            // Not a path; fall through to tokenising.
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts.isEmpty() ? new String[]{line} : parts.toArray(String[]::new);
    }

    /** Finds an executable on the IDE's own PATH, or returns null. */
    public static String onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir.strip(), executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            } catch (RuntimeException ignored) {
                // A malformed PATH entry is not our problem.
            }
        }
        return null;
    }

    /** The shell's environment with the caller's own additions layered on top. */
    public static Map<String, String> environmentWith(String configured, Map<String, String> extra) {
        Map<String, String> all = new HashMap<>(environment(configured));
        all.putAll(extra);
        return all;
    }
}
