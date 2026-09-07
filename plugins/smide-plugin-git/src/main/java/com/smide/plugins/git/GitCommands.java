package com.smide.plugins.git;

import com.smide.api.Ide;
import com.smide.api.execution.ProcessSpec;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The operations that go through the system {@code git} rather than JGit: pull, push,
 * fetch.
 *
 * <p>Deliberately not JGit. Those three are the ones that talk to a remote, and the
 * user's credential helper, SSH agent and proxy settings are configured for the real
 * client. Doing them in-process would mean asking for a password the user has already
 * given to Windows Credential Manager.
 */
public final class GitCommands {

    private GitCommands() {
    }

    public static boolean available() {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String dir : path.split(File.pathSeparator)) {
            try {
                if (Files.isRegularFile(Path.of(dir, windows ? "git.exe" : "git"))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // A malformed PATH entry.
            }
        }
        return false;
    }

    public static void pull(Ide ide, Path root) {
        run(ide, root, "git pull", List.of("git", "pull", "--ff-only"));
    }

    public static void push(Ide ide, Path root) {
        run(ide, root, "git push", List.of("git", "push"));
    }

    public static void fetch(Ide ide, Path root) {
        run(ide, root, "git fetch", List.of("git", "fetch", "--all", "--prune"));
    }

    private static void run(Ide ide, Path root, String title, List<String> command) {
        if (!available()) {
            ide.notifications().error("git not found",
                    "Install Git and put it on PATH; remote operations use it so your credentials apply.");
            return;
        }
        ide.execution().run(new ProcessSpec(title, command, root));
    }
}
