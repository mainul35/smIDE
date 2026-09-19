package com.smide.plugins.web;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher.InstallRecipe;
import com.smide.api.lang.LanguageServerLauncher.ProgressReporter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Language servers published on npm are installed with {@code npm install --prefix
 * ~/.smide/tools/node}, so every plugin shares one {@code node_modules}. Plugins cannot
 * share code, so each npm-based plugin carries its own copy of this class.
 */
final class NpmTools {

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private NpmTools() {
    }

    /** {@code ~/.smide/tools/node}: the npm prefix shared by all npm-installed servers. */
    static Path prefix(Ide ide) {
        return ide.downloads().toolsDir().resolve("node");
    }

    /** The launcher npm writes for a package executable under the shared prefix. */
    static Path binFor(Ide ide, String executable) {
        return prefix(ide).resolve("node_modules").resolve(".bin").resolve(WINDOWS ? executable + ".cmd" : executable);
    }

    static boolean onPath(String executable) {
        return findOnPath(executable).isPresent();
    }

    /** The executable's full path on PATH, trying the Windows suffixes; empty when absent. */
    static Optional<Path> findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        String[] names = WINDOWS
                ? new String[] {executable + ".cmd", executable + ".exe", executable + ".bat", executable}
                : new String[] {executable};
        for (String dir : path.split(File.pathSeparator)) {
            for (String name : names) {
                Path file = Path.of(dir.isBlank() ? "." : dir, name);
                if (Files.isRegularFile(file)) {
                    return Optional.of(file);
                }
            }
        }
        return Optional.empty();
    }

    /** The executable from the shared prefix if installed there, else from PATH. */
    static Optional<Path> locate(Ide ide, String executable) {
        Path local = binFor(ide, executable);
        return Files.isRegularFile(local) ? Optional.of(local) : findOnPath(executable);
    }

    /** The setting Node.js is found by - the same one the web plugin's toolchain writes. */
    static final String NODE_HOME = "node.home";

    /** The folder the node the IDE was pointed at, or downloaded, runs from; null when there is none. */
    static Path nodeDir(Ide ide) {
        String home = ide.settings().get(NODE_HOME, "");
        if (home.isBlank()) {
            return null;
        }
        try {
            Path root = Path.of(home);
            for (Path dir : List.of(root, root.resolve("bin"))) {
                if (Files.isRegularFile(dir.resolve(WINDOWS ? "node.exe" : "node"))) {
                    return dir;
                }
            }
        } catch (RuntimeException e) {
            // Not a path; as good as not set.
        }
        return null;
    }

    /** npm beside that node, else on PATH. */
    static Optional<Path> npm(Ide ide) {
        Path dir = nodeDir(ide);
        if (dir != null) {
            Path npm = dir.resolve(WINDOWS ? "npm.cmd" : "npm");
            if (Files.isRegularFile(npm)) {
                return Optional.of(npm);
            }
        }
        return findOnPath("npm");
    }

    /**
     * PATH with that node's folder first. npm and the launchers it writes run "node" by
     * name, so a Node.js that is not on PATH - one the IDE downloaded - is found only this way.
     */
    static java.util.Map<String, String> environment(Ide ide) {
        Path dir = nodeDir(ide);
        if (dir == null) {
            return java.util.Map.of();
        }
        String path = System.getenv("PATH");
        return java.util.Map.of("PATH", dir + File.pathSeparator + (path == null ? "" : path));
    }

    /** An install that runs {@code npm install --prefix ~/.smide/tools/node <packages>}. */
    static InstallRecipe install(String description, String... packages) {
        return new InstallRecipe() {
            @Override
            public String description() {
                return description;
            }

            @Override
            public void run(Ide ide, ProgressReporter progress) throws Exception {
                Path npm = npm(ide).orElseThrow(() -> new IOException(
                        "npm was not found. Download Node.js from a .js file's editor, or install it from"
                                + " https://nodejs.org, and try again."));
                Path prefix = prefix(ide);
                Files.createDirectories(prefix);
                List<String> command = new ArrayList<>(List.of(npm.toString(), "install", "--no-audit", "--no-fund",
                        "--prefix", prefix.toString()));
                command.addAll(List.of(packages));
                progress.progress("npm install " + String.join(" ", packages), -1);
                ide.downloads().runTool(command, prefix, environment(ide), progress);
                progress.progress("Installed " + String.join(", ", packages), 1);
            }
        };
    }
}
