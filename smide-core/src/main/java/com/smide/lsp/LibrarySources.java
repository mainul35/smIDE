package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.ui.StatusBar;
import javafx.application.Platform;
import org.eclipse.lsp4j.ExecuteCommandParams;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reading the source of a class that lives in a jar.
 *
 * <p>When the language server answers a navigation with a {@code jdt://} URI and no
 * content, the class is in a library with no sources attached. The URI still says exactly
 * which library and which class, which is enough to finish the job: fetch the sources jar
 * from Maven Central if it is not already in the local repository - after asking, because
 * it is the user's machine and the user's network - and then open the file straight out of
 * that jar.
 *
 * <p>Opening it directly is deliberate. Asking the server to reconfigure the project is
 * also done, so that hovers and completion inside that class work afterwards, but it is
 * slow, sometimes refused, and used to end in "reload the project to attach them" - which
 * is not an answer to "show me this code". The reader gets the code now; the attachment
 * catches up behind them.
 */
final class LibrarySources {

    /** The coordinates JDT writes into the URI it hands back. */
    private static final Pattern GROUP = Pattern.compile("maven\\.groupId=/([^=]+)=");
    private static final Pattern ARTIFACT = Pattern.compile("maven\\.artifactId=/([^=]+)=");
    private static final Pattern VERSION = Pattern.compile("maven\\.version=/([^=]+)=");
    /** {@code jdt://contents/<jar or module>/<package>/<Type>.class} */
    private static final Pattern CONTENTS =
            Pattern.compile("^jdt://contents/([^/]+)/([^/]+)/([^/?]+)");
    /** Where in a source file the type is actually declared. */
    private static final String DECLARATION = "\\b(class|interface|enum|record|@interface)\\s+";

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

    /** The class a {@code jdt://} URI points at: its package and its simple name. */
    private record Type(String packageName, String simpleName) {
        String entryPath() {
            return (packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/") + simpleName + ".java";
        }
    }

    // -------------------------------------------------------------- the URI

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

    private static Optional<Type> typeOf(String uri) {
        Matcher matcher = CONTENTS.matcher(uri == null ? "" : uri);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String name = matcher.group(3);
        int dot = name.lastIndexOf('.');
        return Optional.of(new Type(matcher.group(2), dot < 0 ? name : name.substring(0, dot)));
    }

    /** The JDK module a URI names, when it is a JDK class rather than a library one. */
    private static Optional<String> moduleOf(String uri) {
        Matcher matcher = CONTENTS.matcher(uri == null ? "" : uri);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String first = matcher.group(1);
        return first.endsWith(".jar") ? Optional.empty() : Optional.of(first);
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    // ------------------------------------------------------------- the flow

    /**
     * Gets the source of the class a URI names in front of the user.
     *
     * @param open what to do with the file once it exists, and the line to put the caret on
     * @return false when the URI names nothing we can fetch, so the caller can say so
     */
    static boolean show(Ide ide, LspSession session, String uri, Path fileInProject, Consumer<Path> open) {
        Optional<Type> type = typeOf(uri);
        if (type.isEmpty()) {
            return false;
        }
        Optional<String> module = moduleOf(uri);
        if (module.isPresent()) {
            // A JDK class: its source is in the JDK's own src.zip, if this JDK has one.
            return fromJdk(ide, module.get(), type.get(), open);
        }
        Optional<Artifact> found = artifactOf(uri);
        if (found.isEmpty()) {
            return false;
        }
        Artifact artifact = found.get();
        if (Files.isRegularFile(artifact.localPath())) {
            openFromJar(ide, artifact.localPath(), type.get(), artifact.label(), open);
            reconfigureQuietly(session, fileInProject);
            return true;
        }
        boolean yes = ide.window().confirm("Download sources",
                "There are no sources for " + artifact.label() + ", so its code cannot be shown.\n\n"
                        + "Download " + artifact.sourcesJar() + " from Maven Central and open it?");
        if (!yes) {
            ide.statusBar().message("No sources for " + artifact.label());
            return true;
        }
        StatusBar.Progress progress = ide.statusBar().progress("Downloading sources for "
                + artifact.artifactId(), false);
        ide.window().runInBackground(() -> {
            try {
                Files.createDirectories(artifact.localPath().getParent());
                ide.downloads().download(artifact.centralUrl(), artifact.localPath(), progress::update);
                Platform.runLater(() -> {
                    progress.done();
                    openFromJar(ide, artifact.localPath(), type.get(), artifact.label(), open);
                    reconfigureQuietly(session, fileInProject);
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

    /** Pulls one file out of a sources jar, writes it where it can be opened, and opens it. */
    private static void openFromJar(Ide ide, Path jar, Type type, String label, Consumer<Path> open) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(type.entryPath());
            if (entry == null) {
                ide.statusBar().message(label + " has no source for " + type.simpleName());
                return;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                write(ide, type, new String(in.readAllBytes(), StandardCharsets.UTF_8), open);
            }
        } catch (IOException e) {
            ide.notifications().error("Cannot read " + jar.getFileName(), e.getMessage());
        }
    }

    /** The same, for a class in the JDK, whose sources ship as {@code lib/src.zip}. */
    private static boolean fromJdk(Ide ide, String module, Type type, Consumer<Path> open) {
        for (String home : List.of(System.getProperty("java.home", ""),
                System.getenv("JAVA_HOME") == null ? "" : System.getenv("JAVA_HOME"))) {
            if (home.isBlank()) {
                continue;
            }
            Path zip = Path.of(home).resolve("lib").resolve("src.zip");
            if (!Files.isRegularFile(zip)) {
                continue;
            }
            try (ZipFile sources = new ZipFile(zip.toFile())) {
                // Since Java 9 the sources are laid out by module.
                ZipEntry entry = sources.getEntry(module + "/" + type.entryPath());
                if (entry == null) {
                    entry = sources.getEntry(type.entryPath());
                }
                if (entry == null) {
                    continue;
                }
                try (InputStream in = sources.getInputStream(entry)) {
                    write(ide, type, new String(in.readAllBytes(), StandardCharsets.UTF_8), open);
                }
                return true;
            } catch (IOException ignored) {
                // Try the next JDK.
            }
        }
        return false;
    }

    /** Writes the source somewhere real, because every editor feature wants a file. */
    private static void write(Ide ide, Type type, String source, Consumer<Path> open) {
        try {
            Path dir = ide.homeDir().resolve("libraries");
            Files.createDirectories(dir);
            Path file = dir.resolve(type.simpleName() + ".java");
            Files.writeString(file, source);
            open.accept(file);
            ide.statusBar().message("Read-only copy from a library: " + file.getFileName());
        } catch (IOException e) {
            ide.notifications().error("Cannot open library source", e.getMessage());
        }
    }

    /** The line the type is declared on, so the caret lands on it rather than on the licence. */
    static int declarationLine(String source, String simpleName) {
        Pattern pattern = Pattern.compile(DECLARATION + Pattern.quote(simpleName) + "\\b");
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Asks the server to reconfigure the project so the jar is attached from now on.
     *
     * <p>Best effort and silent: the source is already open by this point, so a failure
     * here is not something to put in front of the user.
     */
    private static void reconfigureQuietly(LspSession session, Path fileInProject) {
        Path build = buildFileFor(fileInProject);
        if (build == null) {
            return;
        }
        try {
            session.server().getWorkspaceService()
                    .executeCommand(new ExecuteCommandParams("java.projectConfiguration.update",
                            List.of(Positions.uri(build))))
                    .exceptionally(error -> {
                        System.err.println("smIDE: could not update the project configuration: " + error);
                        return null;
                    });
        } catch (RuntimeException e) {
            System.err.println("smIDE: could not update the project configuration: " + e);
        }
    }

    /** The nearest build file above a source file. */
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
