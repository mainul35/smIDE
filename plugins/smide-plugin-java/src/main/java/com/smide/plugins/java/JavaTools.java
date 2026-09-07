package com.smide.plugins.java;

import com.smide.api.Ide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where the tools are: the JDK, Maven (wrapper first), Gradle (wrapper first), Docker.
 * Settings under {@code java.*} override discovery.
 */
public final class JavaTools {

    public static final String JDK_HOME = "java.jdkHome";
    public static final String PREFER_WRAPPER = "java.preferWrapper";
    public static final String MAVEN_HOME = "java.mavenHome";

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private JavaTools() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    /** The JDK to run things with: the setting, else JAVA_HOME, else the JDK running the IDE. */
    public static Path jdkHome(Ide ide) {
        String setting = ide.settings().get(JDK_HOME, "");
        if (!setting.isBlank() && Files.isDirectory(Path.of(setting))) {
            return Path.of(setting);
        }
        String env = System.getenv("JAVA_HOME");
        if (env != null && !env.isBlank() && Files.isDirectory(Path.of(env))) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("java.home"));
    }

    public static String javaExecutable(Ide ide) {
        Path bin = jdkHome(ide).resolve("bin").resolve(WINDOWS ? "java.exe" : "java");
        return Files.exists(bin) ? bin.toString() : "java";
    }

    /** {@code jpackage} from the same JDK, or null if the JDK has none. */
    public static Optional<String> jdkTool(Ide ide, String name) {
        Path bin = jdkHome(ide).resolve("bin").resolve(WINDOWS ? name + ".exe" : name);
        if (Files.exists(bin)) {
            return Optional.of(bin.toString());
        }
        return onPath(name) ? Optional.of(name) : Optional.empty();
    }

    /** The Maven command for a project root: the wrapper when present, else {@code mvn}. */
    public static List<String> maven(Ide ide, Path projectRoot) {
        boolean preferWrapper = ide.settings().getBoolean(PREFER_WRAPPER, true);
        if (preferWrapper) {
            Path wrapper = wrapperIn(projectRoot, WINDOWS ? "mvnw.cmd" : "mvnw");
            if (wrapper != null) {
                return new ArrayList<>(List.of(wrapper.toString()));
            }
        }
        String home = ide.settings().get(MAVEN_HOME, "");
        if (!home.isBlank()) {
            Path bin = Path.of(home, "bin", WINDOWS ? "mvn.cmd" : "mvn");
            if (Files.exists(bin)) {
                return new ArrayList<>(List.of(bin.toString()));
            }
        }
        return new ArrayList<>(List.of(WINDOWS ? "mvn.cmd" : "mvn"));
    }

    public static List<String> gradle(Ide ide, Path projectRoot) {
        Path wrapper = wrapperIn(projectRoot, WINDOWS ? "gradlew.bat" : "gradlew");
        if (wrapper != null) {
            return new ArrayList<>(List.of(wrapper.toString()));
        }
        return new ArrayList<>(List.of(WINDOWS ? "gradle.bat" : "gradle"));
    }

    /** Walks up from {@code dir} to find a wrapper script, since modules share the root's. */
    private static Path wrapperIn(Path dir, String name) {
        Path current = dir;
        for (int i = 0; i < 6 && current != null; i++) {
            Path candidate = current.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    public static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        String[] names = WINDOWS
                ? new String[]{executable + ".exe", executable + ".cmd", executable + ".bat", executable}
                : new String[]{executable};
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String n : names) {
                try {
                    if (Files.isRegularFile(Path.of(dir, n))) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // Bad PATH entry.
                }
            }
        }
        return false;
    }

    public static boolean mavenAvailable(Ide ide, Path root) {
        List<String> cmd = maven(ide, root);
        return cmd.get(0).contains(java.io.File.separator) || onPath("mvn");
    }

    public static boolean dockerAvailable() {
        return onPath("docker");
    }

    /** Java major version of a JDK home, read from its release file; 0 if unknown. */
    public static int jdkVersion(Path home) {
        Path release = home.resolve("release");
        try {
            for (String line : Files.readAllLines(release)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    String v = line.substring("JAVA_VERSION=".length()).replace("\"", "").strip();
                    if (v.startsWith("1.")) {
                        v = v.substring(2);
                    }
                    int dot = v.indexOf('.');
                    return Integer.parseInt(dot > 0 ? v.substring(0, dot) : v);
                }
            }
        } catch (Exception ignored) {
            // Unknown.
        }
        return 0;
    }
}
