package com.smide.plugins.terminal;

import com.smide.api.settings.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which shell to launch and with which environment.
 *
 * <p>The {@code terminal.shell} setting wins when it is set. Otherwise Windows gets
 * {@code pwsh.exe} when it is on the PATH and {@code powershell.exe} when it is not; macOS
 * and Linux get {@code $SHELL}, falling back to {@code /bin/bash}.
 */
final class ShellResolver {

    /** Settings key holding the user's shell; empty means "the platform default". */
    static final String SHELL_KEY = "terminal.shell";

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private ShellResolver() {
    }

    static boolean isWindows() {
        return WINDOWS;
    }

    /** The command line to start: the shell executable followed by its arguments. */
    static String[] command(Settings settings) {
        String configured = settings.get(SHELL_KEY, "").strip();
        if (!configured.isEmpty()) {
            return splitCommandLine(configured);
        }
        if (WINDOWS) {
            String pwsh = findOnPath("pwsh.exe");
            String shell = pwsh != null ? pwsh : "powershell.exe";
            // -NoLogo keeps the banner out of a fresh tab; the profile still runs.
            return new String[]{shell, "-NoLogo"};
        }
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = "/bin/bash";
        }
        // macOS convention: terminals start login shells so that PATH from /etc/paths and
        // the user's profile is in place. Linux terminals start plain interactive shells.
        return MAC ? new String[]{shell, "-l"} : new String[]{shell};
    }

    /** The default shell's display name, for the settings page prompt text. */
    static String defaultShellDescription() {
        if (WINDOWS) {
            return findOnPath("pwsh.exe") != null ? "pwsh.exe" : "powershell.exe";
        }
        String shell = System.getenv("SHELL");
        return shell == null || shell.isBlank() ? "/bin/bash" : shell;
    }

    /** The process environment plus the variables that tell programs they have a colour terminal. */
    static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        // ConPTY does its own translation on Windows and ignores TERM, but tools ported from
        // Unix (git's pager, less, ncurses builds) still read it, so it is set everywhere.
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        return env;
    }

    /** Finds an executable on the PATH, or returns null. */
    static String findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
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

    /**
     * Splits a configured shell line into executable and arguments. A path that exists as a
     * whole (spaces included, e.g. {@code C:\Program Files\PowerShell\7\pwsh.exe}) is kept in
     * one piece; otherwise the line is split on whitespace, honouring double quotes.
     */
    static String[] splitCommandLine(String line) {
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
}
