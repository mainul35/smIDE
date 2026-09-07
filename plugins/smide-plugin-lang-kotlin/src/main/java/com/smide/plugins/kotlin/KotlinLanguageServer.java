package com.smide.plugins.kotlin;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.workspace.Workspace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Launches fwcd's kotlin-language-server. The release zip unpacks to a {@code server/}
 * folder, which the install recipe places under
 * {@code ~/.smide/tools/kotlin-language-server}; a copy the user put on PATH is used
 * as a fallback.
 */
final class KotlinLanguageServer implements LanguageServerLauncher {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String SERVER_ZIP =
            "https://github.com/fwcd/kotlin-language-server/releases/latest/download/server.zip";
    private static final String EXECUTABLE =
            WINDOWS ? "kotlin-language-server.bat" : "kotlin-language-server";

    @Override
    public String serverId() {
        return "kotlin-language-server";
    }

    @Override
    public String displayName() {
        return "Kotlin Language Server";
    }

    /** {@code ~/.smide/tools/kotlin-language-server}; the zip is unpacked here as-is. */
    private static Path installDir(Ide ide) {
        return ide.downloads().toolsDir().resolve("kotlin-language-server");
    }

    private static Path installedExecutable(Ide ide) {
        return installDir(ide).resolve("server").resolve("bin").resolve(EXECUTABLE);
    }

    private static Optional<Path> locate(Ide ide) {
        Path local = installedExecutable(ide);
        if (Files.isRegularFile(local)) {
            return Optional.of(local);
        }
        return findOnPath("kotlin-language-server");
    }

    /** Finds an executable on PATH, trying the Windows script and binary suffixes too. */
    private static Optional<Path> findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        String[] candidates = WINDOWS
                ? new String[] {name + ".bat", name + ".cmd", name + ".exe", name}
                : new String[] {name};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String candidate : candidates) {
                Path file = Path.of(dir, candidate);
                if (Files.isRegularFile(file)) {
                    return Optional.of(file);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isInstalled(Ide ide) {
        return locate(ide).isPresent();
    }

    @Override
    public Optional<InstallRecipe> installRecipe() {
        return Optional.of(new InstallRecipe() {
            @Override
            public String description() {
                return "Download the latest kotlin-language-server release into "
                        + "~/.smide/tools/kotlin-language-server. It runs on the Java runtime found on PATH.";
            }

            @Override
            public void run(Ide ide, ProgressReporter progress) throws Exception {
                Path dir = installDir(ide);
                Path zip = dir.resolve("server.zip");
                progress.progress("Downloading kotlin-language-server", -1);
                ide.downloads().download(SERVER_ZIP, zip, progress);
                progress.progress("Extracting kotlin-language-server", -1);
                ide.downloads().extract(zip, dir, progress);
                Files.deleteIfExists(zip);
                if (!Files.isRegularFile(installedExecutable(ide))) {
                    throw new IOException("The archive did not contain server/bin/" + EXECUTABLE);
                }
                progress.progress("kotlin-language-server installed", 1);
            }
        });
    }

    @Override
    public List<String> command(Ide ide, Workspace workspace) {
        Path executable = locate(ide)
                .orElseThrow(() -> new IllegalStateException("kotlin-language-server is not installed"));
        return List.of(executable.toString());
    }
}
