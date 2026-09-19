package com.smide.plugins.java;

import com.smide.api.Ide;
import com.smide.api.project.ProjectImporter;
import com.smide.api.project.ProjectModel;
import com.smide.api.project.ProjectModel.BuildTask;
import com.smide.api.project.ProjectModel.ProjectModule;
import com.smide.api.workspace.Workspace;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads {@code pom.xml} files into modules, source roots and tasks. Reading the model
 * rather than asking Maven keeps import instant; the real classpath comes later from the
 * language server, which runs Maven itself.
 */
public final class MavenImporter implements ProjectImporter {

    public static final List<String> LIFECYCLE = List.of(
            "clean", "validate", "compile", "test", "package", "verify", "install", "deploy");

    private final JavaProjectRegistry registry;

    public MavenImporter(JavaProjectRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String id() {
        return "maven";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean detects(Path root) {
        return Files.isRegularFile(root.resolve("pom.xml")) || !nestedPoms(root).isEmpty();
    }

    /**
     * Maven builds a level or two below the folder that was opened.
     *
     * <p>A repository is very often a root holding {@code server/}, {@code web/} and
     * {@code docs/} with no aggregator pom at the top. Opening that root should still
     * give a Java project, so when there is no {@code pom.xml} beside the folder the
     * children are searched - two levels, which covers {@code server/} and
     * {@code backend/service/} without walking a whole tree looking for build files.
     */
    private static List<Path> nestedPoms(Path root) {
        List<Path> poms = new ArrayList<>();
        collectPoms(root, poms, 0);
        return poms;
    }

    private static void collectPoms(Path dir, List<Path> found, int depth) {
        if (depth > 2 || found.size() >= 12) {
            return;
        }
        try (java.util.stream.Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".") || SKIPPED.contains(name)) {
                    continue;
                }
                if (Files.isRegularFile(child.resolve("pom.xml"))) {
                    found.add(child.resolve("pom.xml"));
                } else {
                    collectPoms(child, found, depth + 1);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // An unreadable directory holds no project we can use.
        }
    }

    private static final Set<String> SKIPPED = Set.of(
            "target", "build", "out", "node_modules", "dist", "bin", "obj", "k8s", "docs", "web");

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace) throws IOException {
        return importProject(ide, workspace, workspace.root());
    }

    /** The build at {@code root}, which may be a folder inside the workspace; kept under the workspace. */
    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace, Path root) throws IOException {
        List<ProjectModule> modules = new ArrayList<>();
        List<JavaProjectInfo.WebModule> webModules = new ArrayList<>();
        List<BuildTask> tasks = new ArrayList<>();
        Set<String> profiles = new LinkedHashSet<>();
        boolean[] flags = new boolean[2];
        int[] javaVersion = {0};

        Path rootPom = root.resolve("pom.xml");
        Model rootModel = null;
        if (Files.isRegularFile(rootPom)) {
            rootModel = read(rootPom);
            collect(root, rootModel, modules, webModules, tasks, profiles, flags, javaVersion, 0);
        } else {
            // No aggregator at the top: adopt each nested build as a module of this workspace.
            for (Path pom : nestedPoms(root)) {
                try {
                    Model model = read(pom);
                    if (rootModel == null) {
                        rootModel = model;
                    }
                    collect(pom.getParent(), model, modules, webModules, tasks, profiles, flags, javaVersion, 0);
                } catch (IOException e) {
                    System.err.println("smIDE: cannot read " + pom + ": " + e);
                }
            }
            if (rootModel == null) {
                return null; // Nothing Maven-shaped after all; let another importer try.
            }
        }

        String name = rootModel.getArtifactId() == null ? root.getFileName().toString() : rootModel.getArtifactId();
        ProjectModel model = new ProjectModel("maven", name, root, modules, tasks);
        SourceScanner.Result scanned = SourceScanner.scan(model);
        String version = rootModel.getVersion() != null ? rootModel.getVersion()
                : rootModel.getParent() != null ? rootModel.getParent().getVersion() : "";
        registry.put(workspace.root(), new JavaProjectInfo("maven", model, flags[0], flags[1], scanned.mains(), scanned.tests(),
                rootModel.getPackaging() == null ? "jar" : rootModel.getPackaging(), name, version,
                webModules, new ArrayList<>(profiles), javaVersion[0]));
        return model;
    }

    private void collect(Path dir, Model model, List<ProjectModule> modules,
                         List<JavaProjectInfo.WebModule> webModules, List<BuildTask> tasks,
                         Set<String> profiles, boolean[] flags, int[] javaVersion, int depth) {
        String name = model.getArtifactId() == null ? dir.getFileName().toString() : model.getArtifactId();
        Path src = resolve(dir, model.getBuild() == null ? null : model.getBuild().getSourceDirectory(), "src/main/java");
        Path test = resolve(dir, model.getBuild() == null ? null : model.getBuild().getTestSourceDirectory(), "src/test/java");
        Path out = resolve(dir, model.getBuild() == null ? null : model.getBuild().getOutputDirectory(), "target/classes");
        List<Path> resources = new ArrayList<>();
        Path res = dir.resolve("src/main/resources");
        if (Files.isDirectory(res)) {
            resources.add(res);
        }
        Path testRes = dir.resolve("src/test/resources");
        List<Path> testRoots = new ArrayList<>();
        if (Files.isDirectory(test)) {
            testRoots.add(test);
        }
        if (Files.isDirectory(testRes)) {
            testRoots.add(testRes);
        }
        List<Path> sourceRoots = new ArrayList<>();
        if (Files.isDirectory(src)) {
            sourceRoots.add(src);
        }
        if (!"pom".equals(model.getPackaging()) || !sourceRoots.isEmpty()) {
            modules.add(new ProjectModule(name, dir, sourceRoots, testRoots, resources, out));
        } else {
            modules.add(new ProjectModule(name, dir, List.of(), List.of(), List.of(), out));
        }

        if ("war".equals(model.getPackaging())) {
            // What a servlet container can be handed; a Tomcat configuration is offered for it.
            webModules.add(new JavaProjectInfo.WebModule(name, dir));
        }

        for (String phase : LIFECYCLE) {
            tasks.add(new BuildTask(phase, "mvn " + phase + " in " + name, "Lifecycle/" + name,
                    List.of("mvn", phase), dir));
        }
        if (model.getBuild() != null) {
            for (Plugin p : model.getBuild().getPlugins()) {
                String artifact = p.getArtifactId() == null ? "" : p.getArtifactId();
                String prefix = artifact.replace("-maven-plugin", "").replace("maven-", "").replace("-plugin", "");
                for (String goal : knownGoals(artifact)) {
                    tasks.add(new BuildTask(prefix + ":" + goal, "mvn " + prefix + ":" + goal + " in " + name,
                            "Plugins/" + name, List.of("mvn", prefix + ":" + goal), dir));
                }
                if (artifact.contains("spring-boot")) {
                    flags[0] = true;
                }
            }
        }
        for (Dependency d : model.getDependencies()) {
            String a = d.getArtifactId() == null ? "" : d.getArtifactId();
            String g = d.getGroupId() == null ? "" : d.getGroupId();
            if (a.startsWith("spring-boot") || g.equals("org.springframework.boot")) {
                flags[0] = true;
            }
            if (a.contains("spring-lens")) {
                flags[1] = true;
            }
        }
        if (model.getParent() != null && "spring-boot-starter-parent".equals(model.getParent().getArtifactId())) {
            flags[0] = true;
        }
        for (Profile p : model.getProfiles()) {
            if (p.getId() != null) {
                profiles.add(p.getId());
            }
        }
        if (javaVersion[0] == 0) {
            javaVersion[0] = releaseOf(model);
        }

        if (depth < 6) {
            for (String module : model.getModules()) {
                Path child = dir.resolve(module).normalize();
                Path pom = Files.isDirectory(child) ? child.resolve("pom.xml") : child;
                if (Files.isRegularFile(pom)) {
                    try {
                        collect(pom.getParent(), read(pom), modules, webModules, tasks, profiles, flags, javaVersion, depth + 1);
                    } catch (IOException e) {
                        System.err.println("smIDE: cannot read " + pom + ": " + e);
                    }
                }
            }
        }
    }

    private static int releaseOf(Model model) {
        java.util.Properties props = model.getProperties();
        for (String key : List.of("maven.compiler.release", "maven.compiler.target", "java.version", "maven.compiler.source")) {
            String v = props.getProperty(key);
            if (v != null && !v.isBlank()) {
                try {
                    v = v.strip();
                    if (v.startsWith("1.")) {
                        v = v.substring(2);
                    }
                    return Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                    // A property reference; not resolvable here.
                }
            }
        }
        return 0;
    }

    private static List<String> knownGoals(String artifactId) {
        return switch (artifactId) {
            case "spring-boot-maven-plugin" -> List.of("run", "build-image", "repackage");
            case "maven-dependency-plugin" -> List.of("tree", "analyze");
            case "maven-surefire-plugin" -> List.of("test");
            case "exec-maven-plugin" -> List.of("java", "exec");
            case "javafx-maven-plugin" -> List.of("run");
            case "maven-shade-plugin" -> List.of("shade");
            case "jib-maven-plugin" -> List.of("build", "dockerBuild");
            case "flyway-maven-plugin" -> List.of("migrate", "info");
            case "liquibase-maven-plugin" -> List.of("update", "status");
            case "versions-maven-plugin" -> List.of("display-dependency-updates");
            default -> List.of();
        };
    }

    private static Path resolve(Path dir, String configured, String fallback) {
        String value = configured == null || configured.isBlank() || configured.contains("${") ? fallback : configured;
        return dir.resolve(value).normalize();
    }

    /** Reads one pom. Public because the run configurations read them too. */
    public static Model read(Path pom) throws IOException {
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            MavenXpp3Reader xpp = new MavenXpp3Reader();
            xpp.setAddDefaultEntities(false);
            return xpp.read(reader, false);
        } catch (org.codehaus.plexus.util.xml.pull.XmlPullParserException e) {
            throw new IOException("Malformed pom.xml: " + e.getMessage(), e);
        }
    }
}
