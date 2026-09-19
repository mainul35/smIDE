package com.smide.plugins.java;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The Eclipse JDT Language Server: downloaded once from the Eclipse snapshot site into
 * {@code ~/.smide/tools/jdtls}, launched with a workspace-specific data directory.
 */
public final class JdtLauncher implements LanguageServerLauncher {

    public static final String SERVER_ID = "jdtls";
    private static final String SNAPSHOTS = "https://download.eclipse.org/jdtls/snapshots/";
    public static final String JVM_ARGS_KEY = "java.jdtls.jvmArgs";

    private final Ide ide;
    private final JavaProjectRegistry registry;

    public JdtLauncher(Ide ide, JavaProjectRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    @Override
    public String serverId() {
        return SERVER_ID;
    }

    @Override
    public String displayName() {
        return "JDT Language Server";
    }

    public Path home() {
        return ide.downloads().toolsDir().resolve("jdtls");
    }

    @Override
    public boolean isInstalled(Ide ide) {
        return launcherJar().isPresent();
    }

    private Optional<Path> launcherJar() {
        Path plugins = home().resolve("plugins");
        if (!Files.isDirectory(plugins)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(plugins)) {
            return files.filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_")
                    && p.toString().endsWith(".jar")).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<InstallRecipe> installRecipe() {
        return Optional.of(new InstallRecipe() {
            @Override
            public String description() {
                return "Downloads the latest JDT Language Server build (about 50 MB) from download.eclipse.org.";
            }

            @Override
            public void run(Ide ide, ProgressReporter progress) throws Exception {
                install(ide, progress);
            }
        });
    }

    /** Downloads and unpacks the latest snapshot, replacing any earlier install. */
    public void install(Ide ide, ProgressReporter progress) throws Exception {
        if (serverJdk(JavaTools.jdks(ide)).isEmpty()) {
            throw new IllegalStateException("JDT Language Server needs a JDK 21 or newer to run on, and none was found."
                    + " Install one - projects can still build with older JDKs of their own.");
        }
        progress.progress("Looking up the latest build", -1);
        String latest = ide.downloads().fetchText(SNAPSHOTS + "latest.txt").strip();
        if (!latest.endsWith(".tar.gz")) {
            throw new IOException("Unexpected latest.txt content: " + latest);
        }
        Path archive = ide.downloads().toolsDir().resolve(latest);
        ide.downloads().download(SNAPSHOTS + latest, archive, progress);
        Path target = home();
        deleteTree(target);
        Files.createDirectories(target);
        ide.downloads().extract(archive, target, progress);
        Files.deleteIfExists(archive);
        Files.writeString(target.resolve("VERSION"), latest);
        progress.progress("Installed " + latest, 1);
    }

    public String installedVersion() {
        try {
            return Files.readString(home().resolve("VERSION")).strip();
        } catch (IOException e) {
            return isInstalled(ide) ? "unknown build" : "not installed";
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Override
    public List<String> command(Ide ide, Workspace workspace) {
        Path launcher = launcherJar().orElseThrow(() -> new IllegalStateException("JDT LS is not installed"));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String config;
        if (os.contains("win")) {
            config = "config_win";
        } else if (os.contains("mac")) {
            config = arch.contains("aarch64") || arch.contains("arm") ? "config_mac_arm" : "config_mac";
        } else {
            config = arch.contains("aarch64") || arch.contains("arm") ? "config_linux_arm" : "config_linux";
        }
        if (!Files.isDirectory(home().resolve(config))) {
            config = os.contains("win") ? "config_win" : os.contains("mac") ? "config_mac" : "config_linux";
        }
        List<String> cmd = new ArrayList<>();
        /* The newest JDK there is, not the project's: the server needs 21 or later to run at
           all, and a Java 17 project's own JDK would stop it starting. What the project
           compiles against is the runtimes it is told about, not what the server runs on. */
        cmd.add(JavaTools.javaExecutable(serverJdk(JavaTools.jdks(ide))
                .map(JavaTools.Jdk::home)
                .orElseGet(() -> JavaTools.projectJdk(ide, workspace, registry).home())));
        cmd.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        cmd.add("-Dosgi.bundles.defaultStartLevel=4");
        cmd.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        cmd.add("-Dlog.level=WARNING");
        cmd.add("-Dfile.encoding=UTF-8");
        String jvmArgs = ide.settings().get(JVM_ARGS_KEY, "-Xmx1G -XX:+UseParallelGC -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90");
        for (String arg : jvmArgs.strip().split("\\s+")) {
            if (!arg.isBlank()) {
                cmd.add(arg);
            }
        }
        cmd.add("--add-modules=ALL-SYSTEM");
        cmd.add("--add-opens");
        cmd.add("java.base/java.util=ALL-UNNAMED");
        cmd.add("--add-opens");
        cmd.add("java.base/java.lang=ALL-UNNAMED");
        // Lombok inside the compiler, or @Data's getters and @RequiredArgsConstructor's constructor are errors.
        Lombok.agentFor(ide, workspace.root()).ifPresent(jar -> cmd.add("-javaagent:" + jar));
        cmd.add("-jar");
        cmd.add(launcher.toString());
        cmd.add("-configuration");
        cmd.add(home().resolve(config).toString());
        cmd.add("-data");
        cmd.add(dataDir(ide, workspace).toString());
        return cmd;
    }

    /** The JDK the server runs on: the newest found that is 21 or later. */
    static Optional<JavaTools.Jdk> serverJdk(List<JavaTools.Jdk> installed) {
        return installed.stream().filter(jdk -> jdk.version() >= 21).findFirst();
    }

    private Path dataDir(Ide ide, Workspace workspace) {
        String key = Integer.toHexString(workspace.root().toString().hashCode());
        Path dir = freeDataDir(ide.homeDir().resolve("jdtls-data"), workspace.name() + "-" + key);
        discardIfJdkChanged(ide, dir, JavaTools.projectJdk(ide, workspace, registry).home());
        return dir;
    }

    /** Data folders this process has taken, kept locked until it exits. */
    private static final Map<Path, java.nio.channels.FileChannel> HELD = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A data folder for a project that no other smIDE process is using.
     *
     * <p>Two servers writing one Eclipse workspace corrupt it: its index is thrown away as
     * broken and rebuilt, and every request waits while that happens. It is easy to get
     * there - smIDE run from source for debugging, opening the same project as the smIDE
     * that started it. The first process keeps the usual folder; another gets a numbered
     * one beside it, which it will find again next time.
     */
    static Path freeDataDir(Path base, String name) {
        for (int i = 1; i <= 10; i++) {
            Path candidate = base.resolve(i == 1 ? name : name + "-" + i);
            if (hold(candidate)) {
                return candidate;
            }
        }
        return base.resolve(name);
    }

    /** Takes a data folder for this process; false when another process holds it. */
    static boolean hold(Path dir) {
        if (HELD.containsKey(dir)) {
            return true;
        }
        try {
            Files.createDirectories(dir.getParent());
            java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    dir.resolveSibling(dir.getFileName() + ".lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            if (channel.tryLock() == null) {
                channel.close();
                return false;
            }
            HELD.put(dir, channel);
            return true;
        } catch (IOException | RuntimeException e) {
            // Locking is not available here: carry on as before rather than refuse to start.
            return true;
        }
    }

    /**
     * Throws the workspace away when it was built against a different JDK.
     *
     * <p>JDT decides each project's JRE container when it imports it, and does not revisit
     * that when the runtimes change later: the log says "JVM Runtimes changed, saving new
     * configuration" and every already-imported project keeps the container it was given.
     * So a workspace imported once against the wrong JDK stays wrong for good - which is
     * what happened when the packaged IDE named its own jlink runtime, and correcting the
     * configuration afterwards changed nothing at all, because the import had happened.
     *
     * <p>A line of text beside the workspace records which JDK it was imported with. When
     * that stops matching, the workspace goes and JDT imports again - a couple of minutes
     * once, against a JDK that has source, rather than a permanently sourceless one.
     */
    private static void discardIfJdkChanged(Ide ide, Path dir, Path projectJdk) {
        Path marker = dir.resolveSibling(dir.getFileName() + ".jdk");
        String current = projectJdk != null && JavaTools.canCompile(projectJdk) ? projectJdk.toString() : "";
        String previous = "";
        try {
            if (Files.isRegularFile(marker)) {
                previous = Files.readString(marker).strip();
            }
        } catch (IOException e) {
            // Unreadable marker: treat as unknown, which re-imports. Safe either way.
        }
        try {
            if (Files.isDirectory(dir) && !current.isEmpty() && !current.equals(previous)) {
                ide.notifications().info("Java project re-import",
                        "This project's JDK changed to " + current + ", so the Java language server's"
                        + " workspace is being rebuilt. Navigation will be ready shortly.");
                deleteTree(dir);
            }
            if (!current.isEmpty()) {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, current);
            }
        } catch (IOException e) {
            // A workspace that could not be cleared is a slow start, not a broken one.
            System.err.println("smIDE: could not reset the Java workspace at " + dir + " - " + e);
        }
    }

    @Override
    public Map<String, String> environment(Ide ide, Workspace workspace) {
        Map<String, String> env = new HashMap<>();
        env.putAll(JavaTools.environment(JavaTools.projectJdk(ide, workspace, registry).home()));
        return env;
    }

    /**
     * The JDKs to compile and navigate against: one per Java release, the project's the default.
     *
     * <p>JDT keys runtimes by execution environment name - JavaSE-21, JavaSE-25 - and gives
     * each module the one its build's release asks for, so a JDK of every release found is
     * worth naming. One per name, since two JDK 21s under one name would collide: the
     * project's own JDK takes its release's slot, then one with sources. Sources named
     * rather than left to be found - declaring the runtime alone gave a container with no
     * source attachment, so a jdt:// declaration in the JDK opened to nothing at all.
     */
    static List<Map<String, Object>> runtimes(List<JavaTools.Jdk> installed, Path projectJdk) {
        JavaTools.Jdk project = projectJdk != null && JavaTools.canCompile(projectJdk)
                ? JavaTools.describe(List.of(projectJdk)).get(0) : null;
        List<JavaTools.Jdk> ordered = new ArrayList<>();
        if (project != null) {
            ordered.add(project);
        }
        ordered.addAll(installed);
        Map<Integer, JavaTools.Jdk> byRelease = new LinkedHashMap<>();
        for (JavaTools.Jdk jdk : ordered) {
            if (jdk.version() > 0) {
                byRelease.putIfAbsent(jdk.version(), jdk);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byRelease.entrySet().stream()
                .sorted(Map.Entry.<Integer, JavaTools.Jdk>comparingByKey().reversed())
                .forEach(entry -> {
                    JavaTools.Jdk jdk = entry.getValue();
                    Map<String, Object> runtime = new HashMap<>();
                    runtime.put("name", "JavaSE-" + (jdk.version() >= 9 ? jdk.version() : "1." + jdk.version()));
                    runtime.put("path", jdk.home().toString());
                    if (project != null && jdk.home().equals(project.home())) {
                        runtime.put("default", true);
                    }
                    Path sources = jdk.home().resolve("lib").resolve("src.zip");
                    if (Files.isRegularFile(sources)) {
                        runtime.put("sources", sources.toString());
                    }
                    out.add(runtime);
                });
        return out;
    }

    @Override
    public Object initializationOptions(Ide ide, Workspace workspace) {
        Map<String, Object> java = new HashMap<>();
        java.put("import", Map.of(
                "maven", Map.of("enabled", true),
                "gradle", Map.of("enabled", true)));
        /* Naming the JDK is what attaches its src.zip.

           Without it the JRE container has no source attachment, and JDT treats even
           java.lang classes as an unidentified archive: it asks Maven Central which
           artifact the jar is, over the network, before it will answer "go to
           declaration". On a machine that cannot reach search.maven.org that request
           hangs until it times out and the navigation fails - for the JDK, of all
           things. With the runtime declared, the sources are simply there. */
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("updateBuildConfiguration", "automatic");
        /* Only when there is a real JDK to name. smIDE ships with a runtime of its own -
           a jlink image with no javac - and naming that gets the whole block rejected:
           "Invalid runtime for JavaSE-21: the path does not point to a JDK", after which
           there is no source attachment at all. Sending nothing leaves JDT on its own
           defaults, which is worse than a JDK and better than an invalid one. */
        List<Map<String, Object>> runtimes = runtimes(JavaTools.jdks(ide),
                JavaTools.launchJdk(ide, workspace, registry).home());
        if (!runtimes.isEmpty()) {
            configuration.put("runtimes", runtimes);
        }
        java.put("configuration", configuration);
        /* Source jars come from the same repository the build already uses, so a
           declaration inside a dependency opens as source instead of sending JDT off to
           identify the jar by checksum. */
        java.put("maven", Map.of("downloadSources", true));
        java.put("eclipse", Map.of("downloadSources", true));
        java.put("autobuild", Map.of("enabled", true));
        java.put("maxConcurrentBuilds", 1);
        java.put("completion", Map.of("guessMethodArguments", true, "favoriteStaticMembers", List.of(
                "org.junit.jupiter.api.Assertions.*", "org.assertj.core.api.Assertions.*", "java.util.Objects.*")));
        java.put("signatureHelp", Map.of("enabled", true));
        java.put("format", Map.of("enabled", true));
        Map<String, Object> settings = new HashMap<>();
        settings.put("java", java);
        Map<String, Object> options = new HashMap<>();
        options.put("settings", settings);
        /* classFileContentsSupport tells JDT it may answer "go to declaration" on a
           library type with a jdt:// URI, whose contents the client then asks for. Without
           it the server has nowhere to send the reader and simply reports nothing. */
        options.put("extendedClientCapabilities", Map.of(
                "classFileContentsSupport", true,
                "progressReportProvider", true));
        options.put("bundles", List.of());
        return options;
    }
}
