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
            "bootRun", "bootJar", "dependencies", "tasks");

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
        Path root = workspace.root();
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
        boolean springBoot = false;
        boolean springLens = false;
        for (Path dir : moduleDirs) {
            String name = dir.equals(root) ? root.getFileName().toString() : root.relativize(dir).toString().replace('\\', ':');
            List<Path> src = existing(dir.resolve("src/main/java"), dir.resolve("src/main/kotlin"));
            List<Path> test = existing(dir.resolve("src/test/java"), dir.resolve("src/test/kotlin"), dir.resolve("src/test/resources"));
            List<Path> res = existing(dir.resolve("src/main/resources"));
            modules.add(new ProjectModule(name, dir, src, test, res, dir.resolve("build/classes")));
            String script = readScript(dir);
            springBoot |= script.contains("org.springframework.boot");
            springLens |= script.contains("spring-lens");
            for (String task : TASKS) {
                String qualified = dir.equals(root) ? task : name + ":" + task;
                tasks.add(new BuildTask(task, "gradle " + qualified, "Tasks/" + name, List.of("gradle", qualified), root));
            }
        }
        ProjectModel model = new ProjectModel("gradle", root.getFileName().toString(), root, modules, tasks);
        SourceScanner.Result scanned = SourceScanner.scan(model);
        registry.put(root, new JavaProjectInfo("gradle", model, springBoot, springLens, scanned.mains(), scanned.tests(),
                "jar", root.getFileName().toString(), "", List.of(), 0));
        return model;
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
