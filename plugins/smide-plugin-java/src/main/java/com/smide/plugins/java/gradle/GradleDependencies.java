package com.smide.plugins.java.gradle;

import com.smide.api.problems.Diagnostic;
import com.smide.plugins.java.maven.LocalRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Gradle build script asks for, and whether Gradle has it.
 *
 * <p>IntelliJ draws a dependency it cannot resolve in red where it is written, and a mistyped
 * coordinate - {@code org.projectlombok:lombo} - is caught as it is typed rather than by a build
 * five minutes later. The same is done here for Gradle: every coordinate in the script is looked
 * for in the folders Gradle downloads into, and one that is in neither is reported on its own
 * characters.
 *
 * <p>Two folders, because a Gradle build resolves from both: its own cache,
 * {@code ~/.gradle/caches/modules-2/files-2.1/<group>/<name>/<version>}, and the local Maven
 * repository, which a script asks for with {@code mavenLocal()} and which holds anything built
 * on this machine. A dependency in either is one a build can use.
 *
 * <p>Nothing is reported when the cache does not exist at all. That is a machine where Gradle
 * has never run, and every line of every script would be red through no fault of the script.
 */
public final class GradleDependencies {

    public static final String SOURCE = "gradle";

    /**
     * A coordinate written as one string: {@code implementation 'g:n:v'}, and Kotlin's
     * {@code implementation("g:n:v")}. The configuration in front of it is what makes a string
     * with a colon in it a dependency rather than a name, a path or a URL.
     */
    private static final Pattern AS_STRING = Pattern.compile(
            "(?m)^[ \\t]*(\\w+)[ \\t]*\\(?[ \\t]*(['\"])"
                    + "([A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+(?::[^'\"\\r\\n]*)?)\\2");

    /** The older map form: {@code implementation group: 'g', name: 'n', version: 'v'}. */
    private static final Pattern AS_MAP = Pattern.compile(
            "group\\s*:\\s*(['\"])([A-Za-z0-9_.\\-]+)\\1\\s*,\\s*name\\s*:\\s*(['\"])([A-Za-z0-9_.\\-]+)\\3"
                    + "(?:\\s*,\\s*version\\s*:\\s*(['\"])([^'\"]+)\\5)?");

    /**
     * Configurations a dependency is declared in. Ends-with rather than a list of every name,
     * because a project adds its own - {@code integrationTestImplementation} - and they all end
     * in one of these.
     */
    private static final List<String> CONFIGURATIONS = List.of(
            "implementation", "api", "compileOnly", "runtimeOnly", "compile", "runtime", "classpath",
            "annotationProcessor", "kapt", "ksp", "providedRuntime", "providedCompile", "developmentOnly",
            "detektPlugins", "errorprone", "lintChecks", "testFixturesApi", "agent");

    private GradleDependencies() {
    }

    /** Gradle's build scripts, in either language. */
    public static boolean isBuildScript(Path file) {
        String name = file == null || file.getFileName() == null ? "" : file.getFileName().toString();
        return name.equals("build.gradle") || name.equals("build.gradle.kts");
    }

    /** The problems in one script: every coordinate neither Gradle nor Maven has here. */
    public static List<Diagnostic> problemsIn(Path file, String text) {
        return problemsIn(file, text, gradleFiles(), mavenRepository());
    }

    /** The same, against folders named outright, which is how this is tested. */
    static List<Diagnostic> problemsIn(Path file, String text, Path cache, Path mavenRepository) {
        if (cache == null) {
            return List.of();
        }
        List<Diagnostic> out = new ArrayList<>();
        for (Coordinate coordinate : coordinatesIn(text)) {
            if (!resolved(cache, mavenRepository, coordinate)) {
                out.add(problem(file, text, coordinate));
            }
        }
        return out;
    }

    /** Looked up once: finding it reads settings.xml, and this runs every twenty seconds. */
    private static volatile Path mavenRepository;

    private static Path mavenRepository() {
        Path found = mavenRepository;
        if (found == null) {
            found = LocalRepository.locate().root();
            mavenRepository = found;
        }
        return found;
    }

    /** A coordinate as it is written, and where its characters are. */
    record Coordinate(String group, String name, String version, int start, int end) {
        String label() {
            return group + ":" + name + (version == null ? "" : ":" + version);
        }
    }

    /** Every dependency the script declares, in either notation. */
    static List<Coordinate> coordinatesIn(String text) {
        List<Coordinate> out = new ArrayList<>();
        Matcher string = AS_STRING.matcher(text);
        while (string.find()) {
            if (!isConfiguration(string.group(1))) {
                continue;
            }
            String[] parts = string.group(3).split(":", -1);
            String version = parts.length > 2 ? parts[2] : null;
            out.add(new Coordinate(parts[0], parts[1], version, string.start(3), string.end(3)));
        }
        Matcher map = AS_MAP.matcher(text);
        while (map.find()) {
            out.add(new Coordinate(map.group(2), map.group(4), map.group(6), map.start(), map.end()));
        }
        return out;
    }

    private static boolean isConfiguration(String word) {
        String lower = word.toLowerCase(java.util.Locale.ROOT);
        for (String configuration : CONFIGURATIONS) {
            if (lower.endsWith(configuration.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a build could use this dependency.
     *
     * <p>A version that is not written out, or that comes from somewhere else - a platform, a
     * version catalogue, a property - is not checked: what the build will ask for is not known
     * here, and guessing it would put red under a line that is perfectly good. The group and the
     * name are still checked, which is what catches a misspelt one.
     */
    private static boolean resolved(Path cache, Path mavenRepository, Coordinate coordinate) {
        Path module = cache.resolve(coordinate.group()).resolve(coordinate.name());
        boolean known = Files.isDirectory(module);
        if (known && (coordinate.version() == null || isComputed(coordinate.version()))) {
            return true;
        }
        if (known && Files.isDirectory(module.resolve(coordinate.version()))) {
            return true;
        }
        return inMavenRepository(mavenRepository, coordinate);
    }

    /** A version written as anything but itself: {@code $springVersion}, {@code ${v}}, {@code 1.+}. */
    private static boolean isComputed(String version) {
        return version.isBlank() || version.contains("$") || version.contains("+") || version.contains("[");
    }

    private static boolean inMavenRepository(Path repository, Coordinate coordinate) {
        if (repository == null) {
            return false;
        }
        Path module = repository;
        for (String part : coordinate.group().split("\\.")) {
            module = module.resolve(part);
        }
        module = module.resolve(coordinate.name());
        if (!Files.isDirectory(module)) {
            return false;
        }
        return coordinate.version() == null || isComputed(coordinate.version())
                || Files.isDirectory(module.resolve(coordinate.version()));
    }

    private static Diagnostic problem(Path file, String text, Coordinate coordinate) {
        int[] start = lineColumn(text, coordinate.start());
        int[] end = lineColumn(text, coordinate.end());
        return new Diagnostic(file, start[0], start[1], end[0], end[1], Diagnostic.Severity.ERROR,
                "Cannot resolve " + coordinate.label() + ": it is neither in the Gradle cache nor in the"
                        + " local Maven repository. Gradle downloads it on the next build; if it never"
                        + " arrives, the coordinates are wrong.",
                SOURCE, Diagnostic.UNRESOLVED);
    }

    /**
     * Where Gradle keeps what it has downloaded, or null when it has never downloaded anything.
     *
     * <p>{@code GRADLE_USER_HOME} first, as Gradle itself reads it, then {@code ~/.gradle}.
     */
    static Path gradleFiles() {
        String configured = System.getenv("GRADLE_USER_HOME");
        Path home = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home", "."), ".gradle");
        Path files = home.resolve("caches").resolve("modules-2").resolve("files-2.1");
        return Files.isDirectory(files) ? files : null;
    }

    private static int[] lineColumn(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, offset - lineStart};
    }
}
