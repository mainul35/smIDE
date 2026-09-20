package com.smide.plugins.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A package.json project: its scripts as tasks, and the packages of a workspace as modules.
 *
 * <p>Read from the file rather than by asking the package manager, which would install
 * something to answer. The scripts are what a reader runs - {@code dev}, {@code build},
 * {@code test} - and which manager runs them is decided by the lock file beside it.
 */
public final class NpmImporter implements ProjectImporter {

    @Override
    public String id() {
        return "npm";
    }

    @Override
    public boolean detects(Path root) {
        return Files.isRegularFile(root.resolve("package.json"));
    }

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace) throws IOException {
        return importProject(ide, workspace, workspace.root());
    }

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace, Path root) throws IOException {
        JsonObject manifest = read(root.resolve("package.json"));
        String manager = manager(root);
        List<ProjectModule> modules = new ArrayList<>();
        List<BuildTask> tasks = new ArrayList<>();
        modules.add(module(root, name(manifest, root)));
        for (Path member : workspaces(root, manifest)) {
            modules.add(module(member, name(read(member.resolve("package.json")), member)));
            for (String script : scripts(read(member.resolve("package.json")))) {
                String where = root.relativize(member).toString().replace('\\', '/');
                tasks.add(new BuildTask(where + ": " + script, manager + " run " + script + " in " + where,
                        "Scripts/" + where, List.of(manager, "run", script), member));
            }
        }
        for (String script : scripts(manifest)) {
            tasks.add(new BuildTask(script, manager + " run " + script, "Scripts", List.of(manager, "run", script), root));
        }
        tasks.add(new BuildTask("install", manager + " install", "Scripts", List.of(manager, "install"), root));
        return new ProjectModel("npm", name(manifest, root), root, modules, tasks);
    }

    /** The manager the project is kept with, by the lock file it has. */
    static String manager(Path root) {
        if (Files.isRegularFile(root.resolve("pnpm-lock.yaml"))) {
            return "pnpm";
        }
        if (Files.isRegularFile(root.resolve("yarn.lock"))) {
            return "yarn";
        }
        if (Files.isRegularFile(root.resolve("bun.lockb"))) {
            return "bun";
        }
        return "npm";
    }

    private static ProjectModule module(Path dir, String name) {
        List<Path> sources = new ArrayList<>();
        for (String where : List.of("src", "app", "lib", "pages")) {
            if (Files.isDirectory(dir.resolve(where))) {
                sources.add(dir.resolve(where));
            }
        }
        if (sources.isEmpty()) {
            sources.add(dir);
        }
        List<Path> tests = new ArrayList<>();
        for (String where : List.of("test", "tests", "__tests__", "spec")) {
            if (Files.isDirectory(dir.resolve(where))) {
                tests.add(dir.resolve(where));
            }
        }
        List<Path> resources = Files.isDirectory(dir.resolve("public")) ? List.of(dir.resolve("public")) : List.of();
        return new ProjectModule(name, dir, sources, tests, resources, dir.resolve("dist"));
    }

    /** The packages of a workspace: {@code "workspaces": ["packages/*"]}, and pnpm's own file. */
    private static List<Path> workspaces(Path root, JsonObject manifest) {
        Set<Path> found = new LinkedHashSet<>();
        List<String> patterns = new ArrayList<>();
        JsonElement declared = manifest.get("workspaces");
        if (declared != null && declared.isJsonArray()) {
            declared.getAsJsonArray().forEach(e -> patterns.add(e.getAsString()));
        } else if (declared != null && declared.isJsonObject() && declared.getAsJsonObject().has("packages")) {
            declared.getAsJsonObject().getAsJsonArray("packages").forEach(e -> patterns.add(e.getAsString()));
        }
        Path pnpm = root.resolve("pnpm-workspace.yaml");
        if (Files.isRegularFile(pnpm)) {
            try {
                for (String line : Files.readString(pnpm, StandardCharsets.UTF_8).split("\\R")) {
                    String entry = line.strip();
                    if (entry.startsWith("- ")) {
                        patterns.add(entry.substring(2).strip().replace("'", "").replace("\"", ""));
                    }
                }
            } catch (IOException | RuntimeException e) {
                // No workspace then; the root package still loads.
            }
        }
        for (String pattern : patterns) {
            if (pattern.endsWith("/*")) {
                Path parent = root.resolve(pattern.substring(0, pattern.length() - 2));
                try (Stream<Path> children = Files.list(parent)) {
                    children.filter(p -> Files.isRegularFile(p.resolve("package.json"))).sorted().forEach(found::add);
                } catch (IOException | RuntimeException e) {
                    // Nothing there.
                }
            } else if (Files.isRegularFile(root.resolve(pattern).resolve("package.json"))) {
                found.add(root.resolve(pattern).normalize());
            }
        }
        return List.copyOf(found);
    }

    private static List<String> scripts(JsonObject manifest) {
        if (manifest == null || !manifest.has("scripts") || !manifest.get("scripts").isJsonObject()) {
            return List.of();
        }
        return List.copyOf(manifest.getAsJsonObject("scripts").keySet());
    }

    private static String name(JsonObject manifest, Path dir) {
        if (manifest != null && manifest.has("name") && manifest.get("name").isJsonPrimitive()) {
            return manifest.get("name").getAsString();
        }
        return dir.getFileName() == null ? "package" : dir.getFileName().toString();
    }

    private static JsonObject read(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return new JsonObject();
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException e) {
            return new JsonObject();
        }
    }
}
