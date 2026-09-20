package com.smide.plugins.java;

import com.smide.api.Ide;
import com.smide.api.project.ProjectImporter;
import com.smide.api.project.ProjectModel;
import com.smide.api.project.ProjectModel.BuildTask;
import com.smide.api.project.ProjectModel.ProjectModule;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle by convention: modules from {@code settings.gradle} includes, source roots
 * under {@code src/main/java}, and the tasks every Java build has.
 */
public final class GradleImporter implements ProjectImporter {

    private static final Pattern INCLUDE = Pattern.compile("include\\s*\\(?\\s*([^)\\n]+)");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    public static final List<String> TASKS = List.of("build", "clean", "assemble", "test", "check", "jar", "run",
            "dependencies", "tasks");

    /** Tasks other plugins know, for a build that uses what they are keyed by - bootRun for the Spring Boot plugin. */
    private static final java.util.Map<String, List<String>> CONTRIBUTED_TASKS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Offers these tasks for each module whose script uses {@code what}: a plugin as
     * {@code plugin:<id>}, or a dependency as {@code group:artifact}.
     */
    public static void addTasksFor(String what, List<String> tasks) {
        CONTRIBUTED_TASKS.put(what, List.copyOf(tasks));
    }

    /** A version as a build script writes it: JavaVersion.VERSION_17, '17', "1.8" or 21. */
    private static final String VERSION =
            "(?:JavaVersion\\.VERSION_(?:1_)?(\\d+)|[\"'](?:1\\.)?(\\d+)[\"']|(?:1\\.)?(\\d+)\\b)";

    /** The ways a build script says which Java it builds for, most binding first. */
    private static final List<Pattern> RELEASE_FORMS = List.of(
            Pattern.compile("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)"),
            Pattern.compile("jvmToolchain\\(\\s*(\\d+)\\s*\\)"),
            Pattern.compile("options\\.release(?:\\.set\\(\\s*|\\s*=\\s*)(\\d+)"),
            Pattern.compile("targetCompatibility\\s*=\\s*" + VERSION),
            Pattern.compile("sourceCompatibility\\s*=\\s*" + VERSION));

    private final JavaProjectRegistry registry;

    public GradleImporter(JavaProjectRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String id() {
        return "gradle";
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public boolean detects(Path root) {
        return Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))
                || Files.isRegularFile(root.resolve("settings.gradle")) || Files.isRegularFile(root.resolve("settings.gradle.kts"));
    }

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace) throws IOException {
        return importProject(ide, workspace, workspace.root());
    }

    /** The build at {@code root}, which may be a folder inside the workspace; kept under the workspace. */
    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace, Path root) throws IOException {
        List<ProjectModule> modules = new ArrayList<>();
        List<BuildTask> tasks = new ArrayList<>();
        List<Path> moduleDirs = new ArrayList<>();
        moduleDirs.add(root);
        for (String include : includes(root)) {
            Path dir = root.resolve(include.replace(':', '/').replaceFirst("^/", "")).normalize();
            if (Files.isDirectory(dir)) {
                moduleDirs.add(dir);
            }
        }
        java.util.Set<String> artifacts = new java.util.LinkedHashSet<>();
        // The wrapper when it can run, else a Gradle on the machine; the plain name when neither.
        List<String> gradle = JavaTools.gradleOrNull(ide, root);
        String command = gradle == null ? "gradle" : gradle.get(0);
        int release = 0;
        List<JavaProjectInfo.WebModule> webModules = new ArrayList<>();
        for (Path dir : moduleDirs) {
            String name = dir.equals(root) ? root.getFileName().toString() : root.relativize(dir).toString().replace('\\', ':');
            List<Path> src = existing(dir.resolve("src/main/java"), dir.resolve("src/main/kotlin"));
            List<Path> test = existing(dir.resolve("src/test/java"), dir.resolve("src/test/kotlin"), dir.resolve("src/test/resources"));
            List<Path> res = existing(dir.resolve("src/main/resources"));
            modules.add(new ProjectModule(name, dir, src, test, res, dir.resolve("build/classes")));
            String script = readScript(dir);
            // The root's script first, since it is the first directory; a module's if the root says nothing.
            if (release == 0) {
                release = releaseOf(script);
            }
            java.util.Set<String> used = artifactsIn(script);
            artifacts.addAll(used);
            if (script.contains("'war'") || script.contains("\"war\"") || script.contains("apply plugin: war")) {
                // The war plugin is how a Gradle build says "this is a web application".
                webModules.add(new JavaProjectInfo.WebModule(name, dir));
            }
            List<String> moduleTasks = new ArrayList<>(TASKS);
            CONTRIBUTED_TASKS.forEach((what, extra) -> {
                if (used.contains(what)) {
                    moduleTasks.addAll(extra);
                }
            });
            for (String task : moduleTasks) {
                String qualified = dir.equals(root) ? task : name + ":" + task;
                tasks.add(new BuildTask(task, "gradle " + qualified, "Tasks/" + name, List.of(command, qualified), root));
            }
        }
        ProjectModel model = new ProjectModel("gradle", root.getFileName().toString(), root, modules, tasks);
        SourceScanner.Result scanned = SourceScanner.scan(model);
        registry.put(workspace.root(), new JavaProjectInfo("gradle", model, java.util.Set.copyOf(artifacts), scanned.mains(), scanned.tests(),
                "jar", root.getFileName().toString(), "", webModules, List.of(), release));
        return model;
    }

    /**
     * The Java release a build script asks for, or 0.
     *
     * <p>Read from the text rather than by running Gradle, so it knows the forms people write:
     * a toolchain first - that is the JDK Gradle itself insists on - then the compiler's
     * release, then target and source compatibility. A value built from a variable is not
     * followed; that is what the project's own JDK setting is for.
     */
    static int releaseOf(String script) {
        if (script == null) {
            return 0;
        }
        for (Pattern form : RELEASE_FORMS) {
            Matcher matcher = form.matcher(script);
            if (matcher.find()) {
                for (int group = 1; group <= matcher.groupCount(); group++) {
                    if (matcher.group(group) != null) {
                        return Integer.parseInt(matcher.group(group));
                    }
                }
            }
        }
        return 0;
    }

    /** A dependency as a script writes it: {@code 'org.projectlombok:lombok:1.18.42'}. */
    private static final Pattern COORDINATES = Pattern.compile("[\"']([\\w.\\-]+):([\\w.\\-]+)(?::[^\"']*)?[\"']");
    /** A plugin: {@code id 'org.springframework.boot' version ...}, {@code apply plugin: 'war'}. */
    private static final Pattern PLUGIN = Pattern.compile("(?:\\bid\\s*\\(?|apply\\s+plugin\\s*:)\\s*[\"']([\\w.\\-]+)[\"']");

    /**
     * What a build script uses, read from its text: {@code group:artifact} for each dependency
     * it names, {@code plugin:<id>} for each plugin. Not Gradle's resolution - a version
     * catalog or a platform is not followed - but what the script plainly says.
     */
    public static java.util.Set<String> artifactsIn(String script) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        Matcher coordinates = COORDINATES.matcher(script);
        while (coordinates.find()) {
            out.add(coordinates.group(1) + ":" + coordinates.group(2));
        }
        Matcher plugin = PLUGIN.matcher(script);
        while (plugin.find()) {
            out.add("plugin:" + plugin.group(1));
        }
        return out;
    }

    private static List<String> includes(Path root) {
        List<String> out = new ArrayList<>();
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = root.resolve(name);
            if (!Files.isRegularFile(settings)) {
                continue;
            }
            try {
                String text = Files.readString(settings, StandardCharsets.UTF_8);
                Matcher m = INCLUDE.matcher(text);
                while (m.find()) {
                    Matcher q = QUOTED.matcher(m.group(1));
                    while (q.find()) {
                        out.add(q.group(1));
                    }
                }
            } catch (IOException ignored) {
                // No modules then.
            }
        }
        return out;
    }

    private static String readScript(Path dir) {
        for (String name : List.of("build.gradle", "build.gradle.kts")) {
            Path script = dir.resolve(name);
            if (Files.isRegularFile(script)) {
                try {
                    return Files.readString(script, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // Treat as empty.
                }
            }
        }
        return "";
    }

    private static List<Path> existing(Path... candidates) {
        List<Path> out = new ArrayList<>();
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                out.add(p);
            }
        }
        return out;
    }
}
