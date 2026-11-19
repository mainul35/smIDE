package com.smide.plugins.terminal;

import com.smide.api.execution.Shell;
import com.smide.api.settings.Settings;

import java.util.HashMap;
import java.util.Map;

/**
 * Which shell this terminal launches and with which environment.
 *
 * <p>The choosing itself lives in {@link Shell}, in the API, and not here. It used to live here,
 * which was the natural place for it right up to the moment something else in the IDE needed to
 * know what the terminal knows: a shell reads the developer's profile, the profile is where their
 * PATH comes from, and anything in the IDE that starts a program is otherwise working from the
 * environment the desktop handed the IDE at login. Two answers to "where is Maven" in one window
 * is one too many, so there is one answer and this is a tab that uses it.
 *
 * <p>What stays here is what is only true of a tab somebody is typing into: the colour variables.
 */
final class ShellResolver {

    /** Settings key holding the user's shell; empty means "the platform default". */
    static final String SHELL_KEY = Shell.SHELL_SETTING;

    private ShellResolver() {
    }

    static boolean isWindows() {
        return Shell.isWindows();
    }

    /** The command line to start: the shell executable followed by its arguments. */
    static String[] command(Settings settings) {
        return Shell.interactive(settings.get(SHELL_KEY, ""));
    }

    /** The default shell's display name, for the settings page prompt text. */
    static String defaultShellDescription() {
        return Shell.defaultDescription();
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
        return Shell.onPath(executable);
    }

    /** Splits a configured shell line into executable and arguments. */
    static String[] splitCommandLine(String line) {
        return Shell.split(line);
    }
}
