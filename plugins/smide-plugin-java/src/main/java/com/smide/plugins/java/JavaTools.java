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

    /**
     * The JDK to compile, run and navigate with.
     *
     * <p>The setting wins outright. Otherwise the candidates are ranked rather than taken
     * in order, because "first one found" picks badly: a machine whose JAVA_HOME points at
     * a runtime bundled with another application - Android Studio's, say - has a JDK that
     * compiles fine but ships no {@code lib/src.zip}, and then Go to Declaration on
     * {@code java.lang.String} opens nothing at all, because there is no source to open.
     * A complete JDK is preferred, then any JDK, then whatever is running the IDE.
     */
    public static Path jdkHome(Ide ide) {
        return jdk(ide).orElseGet(() -> Path.of(System.getProperty("java.home")));
    }

    /**
     * The JDK to compile, run and navigate with, if this machine has one at all.
     *
     * <p>Empty is a real answer, and the reason this returns an Optional. smIDE ships with
     * a Java runtime of its own - a jlink image with no {@code javac} - so the JVM running
     * the IDE stopped being a usable fallback the day it was packaged: JDT rejects it with
     * "Invalid runtime for JavaSE-21: the path does not point to a JDK", and Java support
     * quietly degrades. Better to find nothing and say so than to name something invalid.
     *
     * <p>Candidates are ranked rather than taken in order, because "first one found" picks
     * badly: a JAVA_HOME pointing at a runtime bundled with another application compiles
     * fine and ships no {@code lib/src.zip}, and then Go to Declaration on
     * {@code java.lang.String} opens nothing. A complete JDK first, then any JDK.
     */
    public static Optional<Path> jdk(Ide ide) {
        String setting = ide.settings().get(JDK_HOME, "");
        if (!setting.isBlank() && Files.isDirectory(Path.of(setting))) {
            return Optional.of(Path.of(setting));
        }
        List<Path> candidates = new ArrayList<>();
        String env = System.getenv("JAVA_HOME");
        if (env != null && !env.isBlank() && Files.isDirectory(Path.of(env))) {
            candidates.add(Path.of(env));
        }
        candidates.add(Path.of(System.getProperty("java.home")));
        candidates.addAll(onPath());
        candidates.addAll(installed());
        return candidates.stream().filter(JavaTools::hasSources).findFirst()
                .or(() -> candidates.stream().filter(JavaTools::canCompile).findFirst());
    }

    /** JDKs reachable through the PATH, by where their javac is. */
    private static List<Path> onPath() {
        List<Path> found = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null) {
            return found;
        }
        String javac = WINDOWS ? "javac.exe" : "javac";
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir).resolve(javac);
            if (Files.isRegularFile(candidate)) {
                found.add(candidate.getParent().getParent());
            }
        }
        return found;
    }

    /**
     * JDKs where the platform's packages put them.
     *
     * <p>Looked for rather than waited for, because an application started from a desktop
     * menu has none of the environment a login shell would have given it: no JAVA_HOME, and
     * a PATH that on Linux is often just /usr/bin. The JDK is on the machine; nothing had
     * told us where.
     */
    private static List<Path> installed() {
        String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
        List<String> roots = WINDOWS
                ? List.of(programFiles + "\\Eclipse Adoptium", programFiles + "\\Java",
                          programFiles + "\\Microsoft", programFiles + "\\Zulu",
                          programFiles + "\\Amazon Corretto")
                : List.of("/usr/lib/jvm", "/usr/java", "/opt/java", "/opt/jdk",
                          "/Library/Java/JavaVirtualMachines",
                          System.getProperty("user.home", ".") + "/.sdkman/candidates/java");
        List<Path> found = new ArrayList<>();
        for (String root : roots) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (java.util.stream.Stream<Path> children = Files.list(dir)) {
                children.filter(Files::isDirectory).forEach(home -> {
                    // macOS buries the JDK inside the bundle.
                    Path bundle = home.resolve("Contents").resolve("Home");
                    found.add(Files.isDirectory(bundle) ? bundle : home);
                });
            } catch (java.io.IOException e) {
                // An unreadable directory is simply not a place we found a JDK.
            }
        }
        // Newest first, so java-21 wins over java-17 when both are present.
        found.sort(java.util.Comparator.comparing(Path::toString).reversed());
        return found;
    }

    /** A JDK that can compile: it has javac. */
    public static boolean canCompile(Path home) {
        return Files.isRegularFile(home.resolve("bin").resolve(WINDOWS ? "javac.exe" : "javac"));
    }

    /** A JDK that also carries the class library sources, which navigation needs. */
    public static boolean hasSources(Path home) {
        return canCompile(home) && Files.isRegularFile(home.resolve("lib").resolve("src.zip"));
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
