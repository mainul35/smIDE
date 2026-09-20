package com.smide.plugins.go;

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
 * A Go module: the one named by {@code go.mod}, or the modules a {@code go.work} lists.
 *
 * <p>Read from those files rather than by running the go command, which on a fresh checkout
 * would go to the network before it answered.
 */
public final class GoImporter implements ProjectImporter {

    public static final List<String> TASKS = List.of("build ./...", "test ./...", "vet ./...", "mod tidy", "mod download");
    private static final Pattern MODULE = Pattern.compile("(?m)^\\s*module\\s+(\\S+)");
    private static final Pattern USE_BLOCK = Pattern.compile("(?s)use\\s*\\((.*?)\\)");
    private static final Pattern USE_ONE = Pattern.compile("(?m)^\\s*use\\s+(\\S+)\\s*$");

    @Override
    public String id() {
        return "go";
    }

    @Override
    public boolean detects(Path root) {
        return Files.isRegularFile(root.resolve("go.mod")) || Files.isRegularFile(root.resolve("go.work"));
    }

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace) throws IOException {
        return importProject(ide, workspace, workspace.root());
    }

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace, Path root) throws IOException {
        List<ProjectModule> modules = new ArrayList<>();
        for (Path dir : moduleDirs(root)) {
            modules.add(new ProjectModule(nameOf(dir, root), dir, List.of(dir), List.of(), List.of(), null));
        }
        List<BuildTask> tasks = new ArrayList<>();
        for (String task : TASKS) {
            List<String> command = new ArrayList<>(List.of("go"));
            command.addAll(List.of(task.split(" ")));
            tasks.add(new BuildTask("go " + task, "go " + task, "Go", command, root));
        }
        return new ProjectModel("go", nameOf(root, root.getParent()), root, modules, tasks);
    }

    /** The modules of the build: those a go.work uses, or the one at the root. */
    private static List<Path> moduleDirs(Path root) {
        List<Path> dirs = new ArrayList<>();
        String work = read(root.resolve("go.work"));
        if (!work.isBlank()) {
            Matcher block = USE_BLOCK.matcher(work);
            if (block.find()) {
                for (String line : block.group(1).split("\\R")) {
                    add(dirs, root, line.strip());
                }
            }
            Matcher one = USE_ONE.matcher(work);
            while (one.find()) {
                add(dirs, root, one.group(1));
            }
        }
        if (dirs.isEmpty() && Files.isRegularFile(root.resolve("go.mod"))) {
            dirs.add(root);
        }
        return dirs;
    }

    private static void add(List<Path> dirs, Path root, String entry) {
        String where = entry.strip();
        if (where.isEmpty() || where.startsWith("//")) {
            return;
        }
        Path dir = root.resolve(where).normalize();
        if (Files.isRegularFile(dir.resolve("go.mod")) && !dirs.contains(dir)) {
            dirs.add(dir);
        }
    }

    /** What a module calls itself in go.mod, or the folder's name. */
    private static String nameOf(Path dir, Path parent) {
        Matcher m = MODULE.matcher(read(dir.resolve("go.mod")));
        if (m.find()) {
            String module = m.group(1);
            int slash = module.lastIndexOf('/');
            return slash < 0 ? module : module.substring(slash + 1);
        }
        return dir.getFileName() == null ? "go" : dir.getFileName().toString();
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
