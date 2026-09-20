package com.smide.plugins.python;

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
 * A Python project: one kept with pyproject.toml, requirements.txt, setup.py or Pipfile.
 *
 * <p>Its tasks are the ones its own files say it is kept with - {@code uv}, {@code poetry},
 * {@code pipenv} or plain {@code pip} - and the tests it has.
 */
public final class PythonImporter implements ProjectImporter {

    private static final Pattern NAME = Pattern.compile("(?m)^\\s*name\\s*=\\s*[\"']([^\"']+)[\"']");

    @Override
    public String id() {
        return "python";
    }

    @Override
    public boolean detects(Path root) {
        for (String marker : List.of("pyproject.toml", "requirements.txt", "setup.py", "setup.cfg", "Pipfile")) {
            if (Files.isRegularFile(root.resolve(marker))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace) throws IOException {
        return importProject(ide, workspace, workspace.root());
    }

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace, Path root) throws IOException {
        String pyproject = read(root.resolve("pyproject.toml"));
        List<Path> sources = new ArrayList<>();
        for (String where : List.of("src", "app")) {
            if (Files.isDirectory(root.resolve(where))) {
                sources.add(root.resolve(where));
            }
        }
        if (sources.isEmpty()) {
            sources.add(root);
        }
        List<Path> tests = new ArrayList<>();
        for (String where : List.of("tests", "test")) {
            if (Files.isDirectory(root.resolve(where))) {
                tests.add(root.resolve(where));
            }
        }
        ProjectModule module = new ProjectModule(nameOf(pyproject, root), root, sources, tests, List.of(), null);

        List<BuildTask> tasks = new ArrayList<>();
        String manager = manager(root, pyproject);
        switch (manager) {
            case "uv" -> {
                tasks.add(task("sync", List.of("uv", "sync"), root));
                tasks.add(task("run tests", List.of("uv", "run", "pytest"), root));
                tasks.add(task("build", List.of("uv", "build"), root));
            }
            case "poetry" -> {
                tasks.add(task("install", List.of("poetry", "install"), root));
                tasks.add(task("run tests", List.of("poetry", "run", "pytest"), root));
                tasks.add(task("build", List.of("poetry", "build"), root));
            }
            case "pipenv" -> {
                tasks.add(task("install", List.of("pipenv", "install", "--dev"), root));
                tasks.add(task("run tests", List.of("pipenv", "run", "pytest"), root));
            }
            default -> {
                if (Files.isRegularFile(root.resolve("requirements.txt"))) {
                    tasks.add(task("install requirements", List.of("pip", "install", "-r", "requirements.txt"), root));
                }
                if (Files.isRegularFile(root.resolve("pyproject.toml")) || Files.isRegularFile(root.resolve("setup.py"))) {
                    tasks.add(task("install this project", List.of("pip", "install", "-e", "."), root));
                    tasks.add(task("build", List.of("python", "-m", "build"), root));
                }
                tasks.add(task("run tests", List.of("python", "-m", "pytest"), root));
            }
        }
        return new ProjectModel("python", module.name(), root, List.of(module), tasks);
    }

    private static BuildTask task(String name, List<String> command, Path dir) {
        return new BuildTask(name, String.join(" ", command), "Python", command, dir);
    }

    /** What the project is kept with, by the files beside it. */
    static String manager(Path root, String pyproject) {
        if (Files.isRegularFile(root.resolve("uv.lock")) || pyproject.contains("[tool.uv")) {
            return "uv";
        }
        if (Files.isRegularFile(root.resolve("poetry.lock")) || pyproject.contains("[tool.poetry")) {
            return "poetry";
        }
        if (Files.isRegularFile(root.resolve("Pipfile"))) {
            return "pipenv";
        }
        return "pip";
    }

    private static String nameOf(String pyproject, Path root) {
        Matcher m = NAME.matcher(pyproject);
        return m.find() ? m.group(1) : root.getFileName() == null ? "python" : root.getFileName().toString();
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
