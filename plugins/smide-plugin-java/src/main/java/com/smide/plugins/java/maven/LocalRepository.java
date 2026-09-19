package com.smide.plugins.java.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The folder Maven keeps what it has downloaded in, and what is in it.
 *
 * <p>Found the way Maven finds it: {@code maven.repo.local}, then {@code <localRepository>}
 * in the user's {@code settings.xml}, then in the installation's, then
 * {@code ~/.m2/repository}. A dependency is "resolved" here when the file Maven would put
 * on the class path is in this folder - which is what IntelliJ means by it too: Maven has
 * it, and a build can use it.
 */
public final class LocalRepository {

    /** The element in settings.xml, not the one in the comment every default settings.xml ships with. */
    private static final Pattern SETTING = Pattern.compile("<localRepository>\\s*([^<]+?)\\s*</localRepository>");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(env\\.)?([^}]+)}");

    /** What a dependency's type makes of its file: its extension, and the classifier it implies. */
    private static final Map<String, String[]> TYPES = Map.ofEntries(
            Map.entry("jar", new String[] {"jar", ""}),
            Map.entry("pom", new String[] {"pom", ""}),
            Map.entry("test-jar", new String[] {"jar", "tests"}),
            Map.entry("maven-plugin", new String[] {"jar", ""}),
            Map.entry("ejb", new String[] {"jar", ""}),
            Map.entry("ejb-client", new String[] {"jar", "client"}),
            Map.entry("java-source", new String[] {"jar", "sources"}),
            Map.entry("javadoc", new String[] {"jar", "javadoc"}),
            Map.entry("bundle", new String[] {"jar", ""}));

    private final Path root;

    public LocalRepository(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** The local repository this machine's Maven uses. */
    public static LocalRepository locate() {
        String home = System.getProperty("user.home");
        String property = System.getProperty("maven.repo.local");
        if (property != null && !property.isBlank()) {
            return new LocalRepository(Path.of(property));
        }
        List<Path> settings = new ArrayList<>();
        settings.add(Path.of(home, ".m2", "settings.xml"));
        for (String variable : List.of("MAVEN_HOME", "M2_HOME")) {
            String installed = System.getenv(variable);
            if (installed != null && !installed.isBlank()) {
                settings.add(Path.of(installed, "conf", "settings.xml"));
            }
        }
        for (Path file : settings) {
            String configured = configuredIn(file);
            if (configured != null) {
                return new LocalRepository(Path.of(configured));
            }
        }
        return new LocalRepository(Path.of(home, ".m2", "repository"));
    }

    /** The local repository a settings file names, with its variables filled in; null when it names none. */
    static String configuredIn(Path settings) {
        if (!Files.isRegularFile(settings)) {
            return null;
        }
        try {
            String text = COMMENT.matcher(Files.readString(settings)).replaceAll("");
            Matcher m = SETTING.matcher(text);
            if (!m.find()) {
                return null;
            }
            Matcher variable = VARIABLE.matcher(m.group(1));
            StringBuilder out = new StringBuilder();
            while (variable.find()) {
                String name = variable.group(2);
                String value = variable.group(1) != null ? System.getenv(name) : System.getProperty(name);
                variable.appendReplacement(out, Matcher.quoteReplacement(value == null ? variable.group() : value));
            }
            variable.appendTail(out);
            String path = out.toString();
            return path.contains("${") ? null : path;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The folder one version of one artifact is kept in. */
    public Path versionDir(String groupId, String artifactId, String version) {
        Path dir = root;
        for (String part : groupId.split("\\.")) {
            dir = dir.resolve(part);
        }
        return dir.resolve(artifactId).resolve(version);
    }

    /** Where an artifact's pom is kept. */
    public Path pom(String groupId, String artifactId, String version) {
        return versionDir(groupId, artifactId, version).resolve(artifactId + "-" + version + ".pom");
    }

    /** Where the file a dependency of this type and classifier is resolved to would be kept. */
    public Path artifact(String groupId, String artifactId, String version, String type, String classifier) {
        String[] handler = TYPES.getOrDefault(type == null || type.isBlank() ? "jar" : type,
                new String[] {type, ""});
        String effectiveClassifier = classifier != null && !classifier.isBlank() ? classifier : handler[1];
        return versionDir(groupId, artifactId, version).resolve(artifactId + "-" + version
                + (effectiveClassifier.isEmpty() ? "" : "-" + effectiveClassifier) + "." + handler[0]);
    }

    /**
     * Whether Maven has this artifact.
     *
     * <p>A snapshot fetched from a remote repository is kept under its timestamp -
     * {@code lib-1.0-20260918.101010-3.jar} - and a snapshot built here under its plain name;
     * either counts.
     */
    public boolean has(String groupId, String artifactId, String version, String type, String classifier) {
        Path file = artifact(groupId, artifactId, version, type, classifier);
        if (Files.isRegularFile(file)) {
            return true;
        }
        if (!version.endsWith("-SNAPSHOT")) {
            return false;
        }
        String name = file.getFileName().toString();
        String prefix = artifactId + "-" + version.substring(0, version.length() - "SNAPSHOT".length());
        String suffix = name.substring((artifactId + "-" + version).length());
        Pattern timestamped = Pattern.compile(Pattern.quote(prefix) + "\\d{8}\\.\\d{6}-\\d+" + Pattern.quote(suffix));
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.anyMatch(p -> timestamped.matcher(p.getFileName().toString()).matches());
        } catch (IOException e) {
            return false;
        }
    }

    /** The repository's path as a person reads it: under the home folder as ~. */
    public String shown() {
        String home = System.getProperty("user.home");
        String path = root.toString();
        return home != null && path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }
}
