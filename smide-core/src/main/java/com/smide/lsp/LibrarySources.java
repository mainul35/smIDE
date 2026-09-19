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

    /** Where a chosen src.zip is remembered, so the question is asked once. */
    private static final String JDK_SOURCES_KEY = "java.sourcesZip";

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

    /** A jar, zip or jmod path in a JDT handle, as {@code =project/\/C:\/Users\/...\/x.jar=} writes one once unescaped. */
    private static final Pattern ARCHIVE = Pattern.compile("/[^=`<]*?\\.(?:jar|zip|jmod)(?=[=`<]|$)");

    /**
     * Which class in which archive a {@code jdt://contents/...} URI names: the library jar and
     * {@code javafx/application/Application.class} in it. For a JDK class, whose handle names
     * {@code lib/jrt-fs.jar}, the JDK's {@code src.zip} beside it and the source in its module,
     * when that zip is there. Empty when the URI does not say.
     */
    static Optional<com.smide.editor.LibraryOrigins.Origin> originOf(String uri) {
        Matcher contents = CONTENTS.matcher(uri == null ? "" : uri);
        int query = uri == null ? -1 : uri.indexOf('?');
        if (!contents.find() || query < 0) {
            return Optional.empty();
        }
        String handle = URLDecoder.decode(uri.substring(query + 1), StandardCharsets.UTF_8).replace("\\", "");
        Matcher archive = ARCHIVE.matcher(handle);
        if (!archive.find()) {
            return Optional.empty();
        }
        String text = archive.group();
        // "=project//C:/Users/..." on Windows, "=project//home/..." elsewhere.
        text = text.matches("^/+[A-Za-z]:/.*") ? text.replaceFirst("^/+", "") : text.replaceFirst("^/+", "/");
        Path path;
        try {
            path = Path.of(text);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String packagePath = contents.group(2).replace('.', '/');
        String name = contents.group(3);
        if (path.getFileName() != null && path.getFileName().toString().equals("jrt-fs.jar")) {
            Path zip = path.resolveSibling("src.zip");
            String module = contents.group(1);
            String source = name.endsWith(".class") ? name.substring(0, name.length() - ".class".length()) + ".java" : name;
            return Optional.of(new com.smide.editor.LibraryOrigins.Origin(zip, module + "/" + packagePath + "/" + source));
        }
        return Optional.of(new com.smide.editor.LibraryOrigins.Origin(path, packagePath + "/" + name));
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
        String attached = ide.settings().get(attachmentKey(artifact), "");
        if (!attached.isBlank() && Files.isRegularFile(Path.of(attached))
                && openFromJar(ide, Path.of(attached), type.get(), artifact.label(), open)) {
            return true;
        }
        if (Files.isRegularFile(artifact.localPath())) {
            openFromJar(ide, artifact.localPath(), type.get(), artifact.label(), open);
            reconfigureQuietly(session, fileInProject, artifact);
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
                    reconfigureQuietly(session, fileInProject, artifact);
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    progress.done();
                    ide.notifications().error("No sources for " + artifact.label(),
                            "Maven Central has no sources jar for this version, or it could not be"
                                    + " fetched: " + e.getMessage());
                    attachLibrarySources(ide, artifact, type.get(), open);
                });
            }
        });
        return true;
    }

    /** Pulls one file out of a sources jar, writes it where it can be opened, and opens it. */
    private static boolean openFromJar(Ide ide, Path jar, Type type, String label, Consumer<Path> open) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(type.entryPath());
            if (entry == null) {
                ide.statusBar().message(label + " has no source for " + type.simpleName());
                return false;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                write(ide, type, new String(in.readAllBytes(), StandardCharsets.UTF_8), open, jar, entry.getName());
            }
            return true;
        } catch (IOException e) {
            ide.notifications().error("Cannot read " + jar.getFileName(), e.getMessage());
            return false;
        }
    }

    /** Where a sources jar chosen by hand for one dependency is remembered. */
    private static String attachmentKey(Artifact artifact) {
        return "library.sources." + artifact.groupId() + ":" + artifact.artifactId();
    }

    /**
     * Offers to be told where a dependency's sources are.
     *
     * <p>Reached when Maven Central has no sources jar for that version, which is
     * ordinary for anything published in-house: the jar exists on somebody's disk or
     * behind a private repository, and the IDE has no way to guess it. Asking is the only
     * thing left that beats "no source attached".
     */
    private static void attachLibrarySources(Ide ide, Artifact artifact, Type type, Consumer<Path> open) {
        boolean choose = ide.window().confirm("No sources for " + artifact.artifactId(),
                artifact.label() + " has no sources jar that could be fetched.\n\n"
                        + "If you have one - built locally, or from a private repository -\n"
                        + "choose it now and smIDE will use it for this dependency from now on.\n\n"
                        + "Expected file: " + artifact.sourcesJar());
        if (!choose) {
            return;
        }
        Optional<Path> chosen = ide.window().chooseFile("Choose " + artifact.sourcesJar(),
                artifact.localPath().getParent());
        if (chosen.isEmpty()) {
            return;
        }
        if (openFromJar(ide, chosen.get(), type, artifact.label(), open)) {
            remember(ide, attachmentKey(artifact), chosen.get());
            ide.notifications().info("Sources attached",
                    chosen.get().getFileName() + " will be used for " + artifact.label() + ".");
        }
    }

    /**
     * The same, for a class in the JDK, whose sources ship as {@code lib/src.zip}.
     *
     * <p>Every JDK on the machine is worth looking in, not just the one this process is
     * running on: the installed smIDE runs on a jlink image that has no sources at all,
     * and a desktop launcher passes no {@code JAVA_HOME}, so looking only at those two is
     * how Ctrl+click into {@code IOException} ends at "no source attached" on a machine
     * that has the sources sitting in {@code /usr/lib/jvm}.
     */
    private static boolean fromJdk(Ide ide, String module, Type type, Consumer<Path> open) {
        for (Path zip : sourceZips(ide)) {
            if (openFromZip(ide, zip, module, type, open)) {
                remember(ide, JDK_SOURCES_KEY, zip);
                return true;
            }
        }
        return attachJdkSources(ide, module, type, open);
    }

    /** Reads one class out of a {@code src.zip}, if that zip has it. */
    private static boolean openFromZip(Ide ide, Path zip, String module, Type type,
                                       Consumer<Path> open) {
        if (!Files.isRegularFile(zip)) {
            return false;
        }
        try (ZipFile sources = new ZipFile(zip.toFile())) {
            // Since Java 9 the sources are laid out by module.
            ZipEntry entry = sources.getEntry(module + "/" + type.entryPath());
            if (entry == null) {
                entry = sources.getEntry(type.entryPath());
            }
            if (entry == null) {
                return false;
            }
            try (InputStream in = sources.getInputStream(entry)) {
                write(ide, type, new String(in.readAllBytes(), StandardCharsets.UTF_8), open, zip, entry.getName());
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Every {@code src.zip} worth trying, best first.
     *
     * <p>What was chosen by hand before comes first, then the runtime, then whatever is
     * installed on the machine. A JDK without sources is skipped rather than reported:
     * having four runtimes and sources in one of them is the ordinary case.
     */
    private static List<Path> sourceZips(Ide ide) {
        List<Path> zips = new java.util.ArrayList<>();
        String remembered = ide.settings().get(JDK_SOURCES_KEY, "");
        if (!remembered.isBlank()) {
            zips.add(Path.of(remembered));
        }
        for (String home : List.of(System.getProperty("java.home", ""),
                System.getenv("JAVA_HOME") == null ? "" : System.getenv("JAVA_HOME"))) {
            if (!home.isBlank()) {
                zips.add(Path.of(home).resolve("lib").resolve("src.zip"));
            }
        }
        for (Path root : jdkRoots()) {
            try (java.util.stream.Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory).forEach(jdk -> {
                    zips.add(jdk.resolve("lib").resolve("src.zip"));
                    // macOS keeps the JDK a level further in.
                    zips.add(jdk.resolve("Contents").resolve("Home")
                            .resolve("lib").resolve("src.zip"));
                });
            } catch (IOException | RuntimeException ignored) {
                // A root that cannot be listed simply has nothing to offer.
            }
        }
        return zips.stream().distinct().filter(Files::isRegularFile).toList();
    }

    /** The places a JDK is installed, per platform. */
    private static List<Path> jdkRoots() {
        List<Path> roots = new java.util.ArrayList<>(List.of(
                Path.of("/usr/lib/jvm"),
                Path.of("/usr/java"),
                Path.of("/Library/Java/JavaVirtualMachines"),
                Path.of(System.getProperty("user.home", "."), ".sdkman", "candidates", "java")));
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            for (String vendor : List.of("Eclipse Adoptium", "Java", "Zulu", "Amazon Corretto",
                    "Microsoft", "BellSoft", "Semeru")) {
                roots.add(Path.of(programFiles, vendor));
            }
        }
        return roots.stream().filter(Files::isDirectory).toList();
    }

    /**
     * Says what is missing, and offers to be told where it is.
     *
     * <p>This is the end of the road that used to read "the declaration is in a library
     * with no source attached" and stop. It is a fixable state - the sources are a
     * download or a package away - so the message says which JDK was looked in, what the
     * package is called, and offers a file chooser for a {@code src.zip} that is already
     * on the machine. What is chosen is remembered, so this is asked once.
     */
    private static boolean attachJdkSources(Ide ide, String module, Type type, Consumer<Path> open) {
        String runtime = System.getProperty("java.home", "unknown");
        boolean choose = ide.window().confirm("No sources for the JDK",
                "The JDK class " + type.simpleName() + " is compiled, and no source is attached.\n\n"
                        + "smIDE looked for lib/src.zip in every JDK it could find, starting with\n"
                        + runtime + "\nand found none that contains " + module + ".\n\n"
                        + "Most JDK downloads ship src.zip; Linux packages usually split it out\n"
                        + "(openjdk-21-source on Debian and Ubuntu, java-21-openjdk-src on Fedora).\n\n"
                        + "Choose a src.zip now?");
        if (!choose) {
            ide.statusBar().message("No source attached for " + type.simpleName()
                    + " - Ctrl+click again to attach one");
            return true;
        }
        Optional<Path> chosen = ide.window().chooseFile("Choose the JDK's src.zip",
                Path.of(runtime).resolve("lib"));
        if (chosen.isEmpty()) {
            return true;
        }
        if (openFromZip(ide, chosen.get(), module, type, open)) {
            remember(ide, JDK_SOURCES_KEY, chosen.get());
            ide.notifications().info("Sources attached",
                    chosen.get() + " will be used for JDK classes from now on.");
            return true;
        }
        ide.notifications().error("Not the sources for this JDK",
                chosen.get().getFileName() + " has no " + module + "/" + type.entryPath()
                        + " in it. A src.zip from a different Java version will not have"
                        + " the same modules; pick the one beside the JDK being compiled against.");
        return true;
    }

    /** Keeps a chosen archive, so the question is asked once rather than every time. */
    private static void remember(Ide ide, String key, Path archive) {
        if (!archive.toString().equals(ide.settings().get(key, ""))) {
            ide.settings().set(key, archive.toString());
        }
    }

    /** Writes the source somewhere real, because every editor feature wants a file. */
    private static void write(Ide ide, Type type, String source, Consumer<Path> open, Path archive, String entry) {
        try {
            /* Under the archive's name and the entry's own path: two libraries' Application.java
               no longer take turns in one file, and where it came from is remembered, so Select
               Opened File can find it in its library - also after a restart brings the tab back. */
            Path file = ide.homeDir().resolve("libraries").resolve("sources")
                    .resolve(archive.getFileName().toString()).resolve(entry).normalize();
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                file.toFile().setWritable(true);
            }
            Files.writeString(file, source);
            com.smide.editor.LibraryOrigins.remember(ide.homeDir(), file, archive, entry);
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
    private static void reconfigureQuietly(LspSession session, Path fileInProject, Artifact artifact) {
        Path build = buildFileFor(fileInProject);
        /* Once per library per session. It re-imports the module, which in a large reactor
           keeps the server busy for a long while - and it used to happen on every click into
           the same library, so each navigation queued behind the last one's re-import. */
        if (build == null || !RECONFIGURED.add(build + "|" + artifact.label())) {
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

    /** Libraries whose project has already been asked to attach their sources this session. */
    private static final java.util.Set<String> RECONFIGURED = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
