package com.smide.plugins.java;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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

    public JdtLauncher(Ide ide) {
        this.ide = ide;
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
        int jdk = JavaTools.jdkVersion(JavaTools.jdkHome(ide));
        if (jdk != 0 && jdk < 21) {
            throw new IllegalStateException("JDT Language Server needs Java 21 or newer; the configured JDK is " + jdk
                    + ". Set the JDK in Settings > Languages > Java.");
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
        cmd.add(JavaTools.javaExecutable(ide));
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
        cmd.add("-jar");
        cmd.add(launcher.toString());
        cmd.add("-configuration");
        cmd.add(home().resolve(config).toString());
        cmd.add("-data");
        cmd.add(dataDir(ide, workspace).toString());
        return cmd;
    }

    private static Path dataDir(Ide ide, Workspace workspace) {
        String key = Integer.toHexString(workspace.root().toString().hashCode());
        return ide.homeDir().resolve("jdtls-data").resolve(workspace.name() + "-" + key);
    }

    @Override
    public Map<String, String> environment(Ide ide, Workspace workspace) {
        Map<String, String> env = new HashMap<>();
        env.put("JAVA_HOME", JavaTools.jdkHome(ide).toString());
        return env;
    }

    /** The JDK to compile and navigate against, named as an execution environment. */
    private static Map<String, Object> runtime(Ide ide) {
        java.nio.file.Path home = JavaTools.jdkHome(ide);
        int version = JavaTools.jdkVersion(home);
        Map<String, Object> runtime = new HashMap<>();
        runtime.put("name", "JavaSE-" + (version >= 9 ? version : "1." + (version == 0 ? 8 : version)));
        runtime.put("path", home.toString());
        runtime.put("default", true);
        /* Named rather than left to be found. Declaring the runtime alone was not enough
           here: the container came up with no source attachment, so a jdt:// declaration
           opened to nothing at all. */
        java.nio.file.Path sources = home.resolve("lib").resolve("src.zip");
        if (java.nio.file.Files.isRegularFile(sources)) {
            runtime.put("sources", sources.toString());
        }
        return runtime;
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
        configuration.put("runtimes", List.of(runtime(ide)));
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
