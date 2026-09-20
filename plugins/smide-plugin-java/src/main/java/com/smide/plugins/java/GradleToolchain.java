package com.smide.plugins.java;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle, for a project that needs one and has no working wrapper.
 *
 * <p>A Gradle project normally carries {@code gradlew}, which fetches Gradle itself - but the
 * jar that script runs, {@code gradle/wrapper/gradle-wrapper.jar}, is a binary that many
 * repositories do not commit, and then {@code gradlew} stops at "Unable to access jarfile".
 * The IDE then needs a Gradle of its own: one already installed, or the one it offers to
 * download - the version the wrapper asks for, so the build is the one the project expects.
 */
public final class GradleToolchain implements Toolchain {

    public static final String HOME_SETTING = "gradle.home";
    public static final String DOWNLOAD = "https://gradle.org/releases/";
    /** What the current release is, and where it is: {@code {"version": "9.1.0", "downloadUrl": ...}}. */
    static final String CURRENT = "https://services.gradle.org/versions/current";
    private static final Pattern FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]+)\"");
    /** {@code distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip} */
    private static final Pattern DISTRIBUTION_VERSION = Pattern.compile("gradle-([\\d.]+(?:-\\w+)?)-(?:bin|all)\\.zip");

    @Override
    public String id() {
        return "gradle";
    }

    @Override
    public String displayName() {
        return "Gradle";
    }

    @Override
    public String purpose() {
        return "Building, running and testing this project need Gradle, and its wrapper cannot run without"
                + " gradle/wrapper/gradle-wrapper.jar, which this project does not have.";
    }

    @Override
    public String downloadUrl() {
        return DOWNLOAD;
    }

    @Override
    public String homeSetting() {
        return HOME_SETTING;
    }

    /** A Gradle build with no wrapper that can run: with one, the project fetches its own Gradle. */
    @Override
    public boolean isNeededBy(Path root) {
        for (Path dir : buildFolders(root)) {
            if (JavaTools.usableGradleWrapper(dir) == null) {
                return true;
            }
        }
        return false;
    }

    /** The Gradle build folders of a project: its root, or the folders a level or two in. */
    private static List<Path> buildFolders(Path root) {
        List<Path> found = new ArrayList<>();
        collect(root, 0, found);
        return found;
    }

    private static void collect(Path dir, int depth, List<Path> found) {
        if (Files.isRegularFile(dir.resolve("build.gradle")) || Files.isRegularFile(dir.resolve("build.gradle.kts"))
                || Files.isRegularFile(dir.resolve("settings.gradle")) || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
            found.add(dir);
            return;
        }
        if (depth >= 3 || found.size() > 20) {
            return;
        }
        try (java.util.stream.Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString();
                if (!name.startsWith(".") && !name.equals("build") && !name.equals("node_modules")) {
                    collect(child, depth + 1, found);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Nothing to find in a folder that cannot be listed.
        }
    }

    @Override
    public Optional<Path> locate(Ide ide) {
        List<Path> usual = new ArrayList<>(Executables.programFiles("Gradle"));
        usual.add(Path.of("/opt/gradle"));
        usual.add(Executables.home().resolve(".sdkman/candidates/gradle/current"));
        List<Path> all = new ArrayList<>(usual);
        for (Path dir : usual) {
            all.addAll(Executables.subfolders(dir, "gradle-"));
        }
        return Executables.find(ide, HOME_SETTING, "gradle", all, p -> true);
    }

    @Override
    public boolean accepts(Path home) {
        return Executables.in(home, "gradle").isPresent();
    }

    /**
     * The Gradle to download: the version this project's wrapper asks for, so what the IDE runs
     * is what the project was built with; the current release when it asks for none.
     */
    @Override
    public Optional<Download> latestDownload(Ide ide) throws IOException {
        String version = wantedVersion(ide).orElse(null);
        String url;
        if (version == null) {
            String current = ide.downloads().fetchText(CURRENT);
            version = field(current, "version");
            url = field(current, "downloadUrl");
            if (version == null || url == null) {
                return Optional.empty();
            }
        } else {
            url = "https://services.gradle.org/distributions/gradle-" + version + "-bin.zip";
        }
        String sha256 = null;
        try {
            sha256 = ide.downloads().fetchText(url + ".sha256").strip().split("\\s+")[0];
        } catch (IOException | RuntimeException e) {
            // Gradle publishes one for every distribution, but the download is still worth offering.
        }
        return Optional.of(new Download(version, url, sha256, -1, "gradle.org"));
    }

    /** The version an open project's gradle-wrapper.properties asks for, when one does. */
    private static Optional<String> wantedVersion(Ide ide) {
        for (Workspace workspace : ide.workspaces().all()) {
            for (Path dir : buildFolders(workspace.root())) {
                Optional<String> version = versionInWrapper(dir);
                if (version.isPresent()) {
                    return version;
                }
            }
        }
        return Optional.empty();
    }

    /** The version in a build folder's gradle-wrapper.properties: {@code gradle-8.10.2-bin.zip} is 8.10.2. */
    static Optional<String> versionInWrapper(Path buildFolder) {
        Path properties = buildFolder.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties");
        if (!Files.isRegularFile(properties)) {
            return Optional.empty();
        }
        try (java.io.Reader in = Files.newBufferedReader(properties, StandardCharsets.UTF_8)) {
            Properties read = new Properties();
            read.load(in);
            Matcher m = DISTRIBUTION_VERSION.matcher(read.getProperty("distributionUrl", ""));
            return m.find() ? Optional.of(m.group(1)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile(FIELD.pattern().formatted(name)).matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
