package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.ui.StatusBar;
import javafx.application.Platform;
import org.eclipse.lsp4j.ExecuteCommandParams;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetching the sources for a dependency, when going to a declaration lands in a jar that
 * has none.
 *
 * <p>The server answers such a navigation with a {@code jdt://} URI and no content, which
 * is a dead end: the user asked to read the code and got a message instead. The URI does
 * carry the artifact's coordinates, though, so the sources jar can be named exactly - and
 * if the user says so, downloaded from Maven Central into the local repository where the
 * build already looks. The project is then asked to reconfigure, which is what attaches
 * it, and the navigation is tried again.
 *
 * <p>Nothing is downloaded without being asked first: it is the user's machine and the
 * user's network.
 */
final class LibrarySources {

    /** The coordinates JDT writes into the URI it hands back. */
    private static final Pattern GROUP = Pattern.compile("maven\\.groupId=/([^=]+)=");
    private static final Pattern ARTIFACT = Pattern.compile("maven\\.artifactId=/([^=]+)=");
    private static final Pattern VERSION = Pattern.compile("maven\\.version=/([^=]+)=");

    private static final String CENTRAL = "https://repo1.maven.org/maven2/";

    private LibrarySources() {
    }

    /** A dependency, as Maven names one. */
    record Artifact(String groupId, String artifactId, String version) {
        String label() {
            return groupId + ":" + artifactId + ":" + version;
        }

        String sourcesJar() {
            return artifactId + "-" + version + "-sources.jar";
        }

        /** Where the local repository keeps it. */
        Path localPath() {
            Path repo = Path.of(System.getProperty("user.home"), ".m2", "repository");
            for (String part : groupId.split("\\.")) {
                repo = repo.resolve(part);
            }
            return repo.resolve(artifactId).resolve(version).resolve(sourcesJar());
        }

        String centralUrl() {
            return CENTRAL + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + sourcesJar();
        }
    }

    /** The artifact a {@code jdt://} URI belongs to, when it names one. */
    static Optional<Artifact> artifactOf(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        String group = first(GROUP, decoded);
        String artifact = first(ARTIFACT, decoded);
        String version = first(VERSION, decoded);
        return group == null || artifact == null || version == null
                ? Optional.empty()
                : Optional.of(new Artifact(group, artifact, version));
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    /**
     * Offers to download the sources for the library a declaration lives in.
     *
     * @param onAttached run once the sources are in place, to try the navigation again
     * @return false when there is nothing to offer, so the caller can say so instead
     */
    static boolean offer(Ide ide, LspSession session, String uri, Path fileInProject, Runnable onAttached) {
        Optional<Artifact> found = artifactOf(uri);
        if (found.isEmpty()) {
            return false;
        }
        Artifact artifact = found.get();
        if (Files.isRegularFile(artifact.localPath())) {
            // Already downloaded, and still not attached: reconfiguring is what is missing.
            reconfigure(ide, session, fileInProject, artifact, onAttached);
            return true;
        }
        boolean yes = ide.window().confirm("Download sources",
                "There are no sources for " + artifact.label() + ", so its code cannot be shown.\n\n"
                        + "Download " + artifact.sourcesJar() + " from Maven Central into your local"
                        + " repository and attach it?");
        if (!yes) {
            ide.statusBar().message("No sources for " + artifact.label());
            return true;
        }
        StatusBar.Progress progress = ide.statusBar().progress("Downloading sources for "
                + artifact.artifactId(), false);
        ide.window().runInBackground(() -> {
            try {
                Files.createDirectories(artifact.localPath().getParent());
                ide.downloads().download(artifact.centralUrl(), artifact.localPath(),
                        (message, fraction) -> progress.update(message, fraction));
                Platform.runLater(() -> {
                    progress.done();
                    reconfigure(ide, session, fileInProject, artifact, onAttached);
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    progress.done();
                    ide.notifications().error("No sources for " + artifact.label(),
                            "Maven Central has no sources jar for this version, or it could not be"
                                    + " fetched: " + e.getMessage());
                });
            }
        });
        return true;
    }

    /**
     * Asks the language server to reconfigure the project so the new jar is attached, then
     * tries the navigation again.
     */
    private static void reconfigure(Ide ide, LspSession session, Path fileInProject, Artifact artifact,
                                    Runnable onAttached) {
        Path build = buildFileFor(fileInProject);
        if (build == null) {
            onAttached.run();
            return;
        }
        StatusBar.Progress progress = ide.statusBar().progress("Attaching " + artifact.artifactId()
                + " sources", false);
        session.server().getWorkspaceService()
                .executeCommand(new ExecuteCommandParams("java.projectConfiguration.update",
                        List.of(Positions.uri(build))))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    progress.done();
                    if (error != null) {
                        ide.statusBar().message("Sources downloaded. Reload the project to attach them.");
                        return;
                    }
                    /* The server reconfigures asynchronously; a moment's grace beats asking
                       the user to click again. */
                    javafx.animation.PauseTransition wait =
                            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
                    wait.setOnFinished(e -> onAttached.run());
                    wait.play();
                }));
    }

    /** The nearest build file above a source file: what a project update is asked for. */
    private static Path buildFileFor(Path file) {
        Path dir = file == null ? null : file.getParent();
        while (dir != null) {
            for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }
}
