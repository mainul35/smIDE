package com.smide.plugins.java;

import com.smide.api.Ide;
import com.smide.api.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where the tools are: the JDK, Maven (wrapper first), Gradle (wrapper first), Docker.
 * Settings under {@code java.*} override discovery.
 */
public final class JavaTools {

    public static final String JDK_HOME = "java.jdkHome";
    public static final String PREFER_WRAPPER = "java.preferWrapper";
    public static final String MAVEN_HOME = "java.mavenHome";

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

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
        String home = System.getProperty("user.home", ".");
        List<String> roots = new ArrayList<>(WINDOWS
                ? List.of(programFiles + "\\Eclipse Adoptium", programFiles + "\\Java",
                          programFiles + "\\Microsoft", programFiles + "\\Zulu",
                          programFiles + "\\Amazon Corretto")
                : List.of("/usr/lib/jvm", "/usr/java", "/opt/java", "/opt/jdk",
                          "/Library/Java/JavaVirtualMachines",
                          home + "/.sdkman/candidates/java"));
        // Where IntelliJ puts the JDKs it downloads, on every platform.
        roots.add(Path.of(home, ".jdks").toString());
        return installedUnder(roots);
    }

    /** The JDK homes directly under each root, macOS bundles unwrapped, newest-looking first. */
    static List<Path> installedUnder(List<String> roots) {
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
        found.sort(Comparator.comparing(Path::toString).reversed());
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

    /** A JDK found on this machine. */
    public record Jdk(Path home, int version, boolean hasSources) {

        /** How it reads in a list: {@code JDK 25   C:\Program Files\...}. */
        public String label() {
            return (version > 0 ? "JDK " + version : "JDK, version unknown") + "   " + home;
        }
    }

    /**
     * The JDK a project gets, and why.
     *
     * @param home      the JDK, or null when this machine has none
     * @param requested the release the build asks for, 0 when it does not say
     * @param reason    how it was chosen, in words
     * @param problem   what is wrong with the choice, for the reader, or null
     */
    public record ProjectJdk(Path home, int version, int requested, String reason, String problem) {
    }

    /**
     * Every JDK on this machine, each once, newest first.
     *
     * <p>Looked for where {@link #jdk} looks: the setting, JAVA_HOME, the PATH and where the
     * platform's packages put them. A path reached twice - a symlink, a JAVA_HOME that is
     * also on the PATH - is one JDK, not two. Within a release, one with its class library
     * sources comes first, since navigation into the JDK needs them.
     */
    public static List<Jdk> jdks(Ide ide) {
        List<Path> candidates = new ArrayList<>();
        String setting = ide.settings().get(JDK_HOME, "");
        if (!setting.isBlank()) {
            candidates.add(Path.of(setting));
        }
        String env = System.getenv("JAVA_HOME");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env));
        }
        candidates.add(Path.of(System.getProperty("java.home")));
        candidates.addAll(onPath());
        candidates.addAll(installed());
        return describe(candidates);
    }

    /** The candidates that are JDKs, described, each once, newest first. */
    static List<Jdk> describe(List<Path> candidates) {
        Map<Path, Jdk> byHome = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            if (candidate == null || !canCompile(candidate)) {
                continue;
            }
            Path home = real(candidate);
            byHome.putIfAbsent(home, new Jdk(home, jdkVersion(home), hasSources(home)));
        }
        List<Jdk> out = new ArrayList<>(byHome.values());
        out.sort(Comparator.comparingInt(Jdk::version).reversed()
                .thenComparing(Jdk::hasSources, Comparator.reverseOrder()));
        return out;
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException | RuntimeException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * The JDK for a project, from what is known about it.
     *
     * <p>In order: the JDK the project was given in its own settings; the IDE's default JDK,
     * when it is new enough for the release the build asks for; otherwise the oldest JDK on
     * the machine that is. The default first, so that a project already working keeps the
     * JDK it works with. The oldest rather than the newest because it is the nearest to what
     * the project was written against: a newer JDK compiles an older release, but runs it on
     * a class library that may have dropped something the code uses.
     *
     * <p>When nothing installed is new enough, the default is kept and the problem says so -
     * a build that fails for a reason already named beats refusing to try.
     *
     * @param projectSetting the JDK set for the project, or null
     * @param fallback       the IDE's default JDK, or null when there is none
     * @param installed      every JDK found, from {@link #jdks}
     * @param requested      the release the build asks for, 0 when unknown
     */
    static ProjectJdk choose(Path projectSetting, Jdk fallback, List<Jdk> installed, int requested) {
        String settingProblem = null;
        if (projectSetting != null) {
            if (canCompile(projectSetting)) {
                int version = jdkVersion(projectSetting);
                String problem = requested > 0 && version > 0 && version < requested
                        ? "This project builds for Java " + requested + ", but the JDK set for it is "
                        + version + ". Choose a newer one in Settings > Languages > Java."
                        : null;
                return new ProjectJdk(projectSetting, version, requested, "set for this project", problem);
            }
            settingProblem = "The JDK set for this project, " + projectSetting
                    + ", is not a JDK - it has no javac - so one is chosen automatically.";
        }
        if (fallback != null && (requested <= 0 || fallback.version() >= requested)) {
            return new ProjectJdk(fallback.home(), fallback.version(), requested, "the default JDK", settingProblem);
        }
        if (requested > 0) {
            Optional<Jdk> enough = installed.stream()
                    .filter(jdk -> jdk.version() >= requested)
                    .min(Comparator.comparingInt(Jdk::version)
                            .thenComparing(Jdk::hasSources, Comparator.reverseOrder()));
            if (enough.isPresent()) {
                Jdk jdk = enough.get();
                return new ProjectJdk(jdk.home(), jdk.version(), requested,
                        "the oldest JDK found that builds Java " + requested, settingProblem);
            }
        }
        int newest = installed.stream().mapToInt(Jdk::version).max().orElse(0);
        String problem = requested > 0
                ? "This project builds for Java " + requested + ", and no JDK that new was found"
                + (newest > 0 ? " - the newest is " + newest : "") + ". Install JDK " + requested
                + " or later, or set its folder in Settings > Languages > Java."
                : "No JDK was found. Install one, or set its folder in Settings > Languages > Java.";
        return fallback == null
                ? new ProjectJdk(null, 0, requested, "no JDK found", problem)
                : new ProjectJdk(fallback.home(), fallback.version(), requested, "the default JDK", problem);
    }

    /** The JDK a workspace builds, runs, tests and debugs with, given what its build asks for. */
    public static ProjectJdk projectJdk(Ide ide, Workspace workspace, int requested) {
        String setting = workspace == null ? "" : workspace.settings().get(JDK_HOME, "");
        return jdkFor(ide, setting.isBlank() ? null : Path.of(setting), requested);
    }

    public static ProjectJdk projectJdk(Ide ide, Workspace workspace, JavaProjectRegistry registry) {
        return projectJdk(ide, workspace, registry == null ? 0 : registry.requestedRelease(workspace));
    }

    /** What a project with this JDK set for it - null for none - and this release would get. */
    public static ProjectJdk jdkFor(Ide ide, Path setting, int requested) {
        Jdk fallback = jdk(ide).map(home -> new Jdk(home, jdkVersion(home), hasSources(home))).orElse(null);
        ProjectJdk chosen = choose(setting, fallback, jdks(ide), requested);
        if (chosen.home() != null) {
            return chosen;
        }
        // Nothing to name: the runtime smIDE runs on, as before, with the problem still said.
        Path own = Path.of(System.getProperty("java.home"));
        return new ProjectJdk(own, jdkVersion(own), requested, chosen.reason(), chosen.problem());
    }

    /** Problems already told, per project, so launching ten times does not say it ten times. */
    private static final Set<String> TOLD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The project's JDK for something about to start, having said once what is wrong with it.
     *
     * <p>A notification rather than a refusal: the build may know better - a toolchain, a
     * property this could not read - and if it does not, it fails in its own words.
     */
    public static ProjectJdk launchJdk(Ide ide, Workspace workspace, JavaProjectRegistry registry) {
        ProjectJdk jdk = projectJdk(ide, workspace, registry);
        if (jdk.problem() != null && workspace != null && TOLD.add(workspace.root() + "|" + jdk.problem())) {
            ide.notifications().warn("Project JDK", workspace.name() + ": " + jdk.problem());
        }
        return jdk;
    }

    /** What a build tool needs to use this JDK: Maven and Gradle both read JAVA_HOME. */
    public static Map<String, String> environment(Path jdk) {
        return jdk == null ? Map.of() : Map.of("JAVA_HOME", jdk.toString());
    }

    public static String javaExecutable(Path jdk) {
        Path bin = jdk.resolve("bin").resolve(WINDOWS ? "java.exe" : "java");
        return Files.exists(bin) ? bin.toString() : "java";
    }

    /** A tool such as {@code jpackage} from this JDK, else from the PATH, else nothing. */
    public static Optional<String> jdkTool(Path jdk, String name) {
        Path bin = jdk.resolve("bin").resolve(WINDOWS ? name + ".exe" : name);
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
        List<String> found = gradleOrNull(ide, projectRoot);
        if (found != null) {
            return found;
        }
        /* Neither a wrapper that can run nor a Gradle on the machine. Rather than letting the
           build fail with "Unable to access jarfile", the offer to install one is put in front
           of the reader, and the run says what is missing. */
        ide.toolchains().offerToInstall("gradle", "This project's Gradle wrapper cannot run: "
                + "gradle/wrapper/gradle-wrapper.jar is missing, and no Gradle was found on this machine.");
        throw new IllegalStateException("Gradle was not found, and this project's gradlew cannot run without"
                + " gradle/wrapper/gradle-wrapper.jar. Download Gradle from the notice, set its folder in"
                + " Settings, or restore the wrapper with \"gradle wrapper\" in the project.");
    }

    /** The Gradle to run - the project's wrapper, or one installed - or null when there is none. */
    public static List<String> gradleOrNull(Ide ide, Path projectRoot) {
        Path wrapper = usableGradleWrapper(projectRoot);
        if (wrapper != null) {
            return new ArrayList<>(List.of(wrapper.toString()));
        }
        return new GradleToolchain().locate(ide)
                .map(gradle -> new ArrayList<>(List.of(gradle.toString()))).orElse(null);
    }

    /**
     * The project's {@code gradlew}, when it can actually run.
     *
     * <p>The script is a launcher for {@code gradle/wrapper/gradle-wrapper.jar}, a binary plenty
     * of repositories leave out of version control; without it the script stops at "Unable to
     * access jarfile", which says nothing about what to do. Then it is not a wrapper worth using.
     */
    public static Path usableGradleWrapper(Path projectRoot) {
        Path script = wrapperIn(projectRoot, WINDOWS ? "gradlew.bat" : "gradlew");
        if (script == null || script.getParent() == null) {
            return null;
        }
        Path jar = script.getParent().resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar");
        return Files.isRegularFile(jar) ? script : null;
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
