package com.smide.plugins.rust;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A Cargo project: the crate at the root, and the members of a workspace.
 *
 * <p>Read from {@code Cargo.toml} rather than by running Cargo, which would want the network
 * on the first call: a crate's name, its sources under {@code src}, its tests under
 * {@code tests}, and the tasks every crate has.
 */
public final class CargoImporter implements ProjectImporter {

    public static final List<String> TASKS = List.of("build", "run", "test", "check", "clippy", "fmt", "clean", "update");
    /** {@code name = "thing"} in the package table. */
    private static final Pattern NAME = Pattern.compile("(?m)^\\s*name\\s*=\\s*\"([^\"]+)\"");
    /** {@code members = ["a", "b/*"]} of a workspace. */
    private static final Pattern MEMBERS = Pattern.compile("(?s)\\[workspace\\].*?members\\s*=\\s*\\[(.*?)\\]");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Override
    public String id() {
        return "cargo";
    }

    @Override
    public boolean detects(Path root) {
        return Files.isRegularFile(root.resolve("Cargo.toml"));
    }

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace) throws IOException {
        return importProject(ide, workspace, workspace.root());
    }

    @Override
    public ProjectModel importProject(Ide ide, Workspace workspace, Path root) throws IOException {
        String manifest = read(root.resolve("Cargo.toml"));
        List<ProjectModule> modules = new ArrayList<>();
        List<BuildTask> tasks = new ArrayList<>();
        List<Path> crates = new ArrayList<>();
        if (Files.isDirectory(root.resolve("src"))) {
            crates.add(root);
        }
        crates.addAll(members(root, manifest));
        if (crates.isEmpty()) {
            crates.add(root);
        }
        for (Path crate : crates) {
            String name = crate.equals(root) ? nameOf(manifest, root) : nameOf(read(crate.resolve("Cargo.toml")), crate);
            modules.add(new ProjectModule(name, crate,
                    existing(crate.resolve("src")), existing(crate.resolve("tests")),
                    existing(crate.resolve("resources"), crate.resolve("assets")), crate.resolve("target")));
        }
        for (String task : TASKS) {
            tasks.add(new BuildTask(task, "cargo " + task, "Cargo", List.of("cargo", task), root));
        }
        return new ProjectModel("cargo", nameOf(manifest, root), root, modules, tasks);
    }

    /** The members of a workspace, including the folders a {@code path/*} entry stands for. */
    private static List<Path> members(Path root, String manifest) {
        Matcher workspace = MEMBERS.matcher(manifest);
        if (!workspace.find()) {
            return List.of();
        }
        Set<Path> found = new LinkedHashSet<>();
        Matcher entries = QUOTED.matcher(workspace.group(1));
        while (entries.find()) {
            String member = entries.group(1);
            if (member.endsWith("/*")) {
                Path parent = root.resolve(member.substring(0, member.length() - 2));
                try (Stream<Path> children = Files.list(parent)) {
                    children.filter(p -> Files.isRegularFile(p.resolve("Cargo.toml"))).sorted().forEach(found::add);
                } catch (IOException | RuntimeException e) {
                    // A member that cannot be listed is a member the build will complain about.
                }
            } else if (Files.isRegularFile(root.resolve(member).resolve("Cargo.toml"))) {
                found.add(root.resolve(member).normalize());
            }
        }
        return List.copyOf(found);
    }

    private static String nameOf(String manifest, Path dir) {
        Matcher m = NAME.matcher(manifest);
        return m.find() ? m.group(1) : dir.getFileName() == null ? "cargo" : dir.getFileName().toString();
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static List<Path> existing(Path... candidates) {
        List<Path> out = new ArrayList<>();
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
