package com.smide.plugins.lombok;

import com.smide.api.Ide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lombok, for the Java language server: whether a project uses it, and the jar to run.
 *
 * <p>The server compiles with Eclipse's compiler, which knows nothing of what Lombok writes
 * into a class - the getters {@code @Data} adds, the constructor {@code @RequiredArgsConstructor}
 * adds for Spring to inject through - unless Lombok runs inside it as a Java agent, as VS Code
 * and IntelliJ arrange. Without it a Lombok project is a page of errors: "the method getStatus()
 * is undefined", "the blank final field may not have been initialized".
 *
 * <p>Only for a project whose build names Lombok. The newest Lombok already on the machine is
 * used - from Maven's local repository or Gradle's cache, where the project's build put it - and
 * one is fetched from Maven Central once, checked against its checksum, when there is none new
 * enough: an old Lombok as an agent can stop a current server from starting at all.
 */
final class Lombok {

    /** The oldest Lombok that works as an agent in the server on current Java. */
    static final int[] OLDEST = {1, 18, 30};
    private static final String CENTRAL = "https://repo1.maven.org/maven2/org/projectlombok/lombok/";
    private static final Pattern RELEASE = Pattern.compile("<release>([^<]+)</release>");
    private static final Pattern VERSION = Pattern.compile("lombok-(\\d+(?:\\.\\d+)*)\\.jar");
    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");
    private static final Set<String> SKIPPED = Set.of("target", "build", "out", "node_modules", ".git", ".gradle", ".idea", "bin");

    private Lombok() {
    }

    /** The jar to run as an agent for this project, if it uses Lombok. May fetch one; call off the UI thread. */
    static Optional<Path> agentFor(Ide ide, Path projectRoot) {
        if (!usedBy(projectRoot)) {
            return Optional.empty();
        }
        Path tools = ide.downloads().toolsDir().resolve("lombok");
        Optional<Path> found = newest(candidates(tools));
        if (found.isPresent() && compare(version(found.get()), OLDEST) >= 0) {
            return found;
        }
        try {
            return Optional.of(fetch(ide, tools));
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: could not fetch Lombok for the language server: " + e);
            // An old one is still better than none.
            return found;
        }
    }

    /** Whether a build file in the project - at the root or a few folders in - mentions Lombok. */
    static boolean usedBy(Path root) {
        List<Path> builds = new ArrayList<>();
        collect(root, 0, builds);
        for (Path build : builds) {
            try {
                if (Files.readString(build, StandardCharsets.UTF_8).contains("lombok")) {
                    return true;
                }
            } catch (IOException | RuntimeException e) {
                // Unreadable: not evidence either way.
            }
        }
        return false;
    }

    private static void collect(Path dir, int depth, List<Path> builds) {
        if (depth > 4 || builds.size() > 200) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.sorted().toList()) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child)) {
                    if (!name.startsWith(".") && !SKIPPED.contains(name)) {
                        collect(child, depth + 1, builds);
                    }
                } else if (BUILD_FILES.contains(name)) {
                    builds.add(child);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A folder that cannot be listed has no build to read.
        }
    }

    /** Lombok jars already on this machine. */
    static List<Path> candidates(Path tools) {
        List<Path> out = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home", "."));
        jarsUnder(home.resolve(".m2").resolve("repository").resolve("org/projectlombok/lombok"), 2, out);
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        Path gradle = gradleHome != null ? Path.of(gradleHome) : home.resolve(".gradle");
        jarsUnder(gradle.resolve("caches/modules-2/files-2.1/org.projectlombok/lombok"), 3, out);
        jarsUnder(tools, 1, out);
        return out;
    }

    private static void jarsUnder(Path dir, int depth, List<Path> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir, depth)) {
            walk.filter(p -> VERSION.matcher(p.getFileName().toString()).matches()).filter(Files::isRegularFile)
                    .forEach(out::add);
        } catch (IOException | RuntimeException e) {
            // Nothing usable there.
        }
    }

    static Optional<Path> newest(List<Path> jars) {
        Path best = null;
        for (Path jar : jars) {
            if (best == null || compare(version(jar), version(best)) > 0) {
                best = jar;
            }
        }
        return Optional.ofNullable(best);
    }

    static int[] version(Path jar) {
        Matcher m = VERSION.matcher(jar.getFileName().toString());
        if (!m.matches()) {
            return new int[0];
        }
        String[] parts = m.group(1).split("\\.");
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Integer.parseInt(parts[i]);
        }
        return v;
    }

    static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** The newest Lombok from Maven Central, checked against its published SHA-1. */
    private static Path fetch(Ide ide, Path tools) throws IOException {
        Matcher m = RELEASE.matcher(ide.downloads().fetchText(CENTRAL + "maven-metadata.xml"));
        if (!m.find()) {
            throw new IOException("Maven Central did not say which Lombok is newest");
        }
        String version = m.group(1).strip();
        String name = "lombok-" + version + ".jar";
        Path jar = tools.resolve(name);
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        Path part = tools.resolve(name + ".part");
        ide.downloads().download(CENTRAL + version + "/" + name, part, (message, fraction) -> {
        });
        String expected = ide.downloads().fetchText(CENTRAL + version + "/" + name + ".sha1").strip().split("\\s+")[0];
        String actual;
        try {
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(part)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(part);
            throw new IOException("the download of " + name + " does not match its checksum");
        }
        Files.move(part, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }
}
