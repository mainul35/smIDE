package com.smide.project;

import com.smide.api.project.ProjectImporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Folders inside a workspace that hold a build some importer recognises, shallowest first:
 * where to look when the workspace's own root has none.
 *
 * <p>Breadth first, a few levels down, skipping what is never a project of its own - hidden
 * folders, build output, dependencies - and not looking inside a build once one is found,
 * since its modules are its own business.
 */
final class BuildFolders {

    static final int MAX_DEPTH = 4;
    private static final int MAX_FOLDERS = 2000;
    private static final Set<String> NOT_PROJECTS = Set.of(
            "target", "build", "out", "bin", "obj", "dist", "node_modules", "vendor", "venv", "__pycache__");

    private BuildFolders() {
    }

    static List<Path> below(Path root, List<ProjectImporter> importers) {
        List<Path> found = new ArrayList<>();
        Deque<Path> queue = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < MAX_FOLDERS) {
            Path dir = queue.poll();
            int depth = depths.poll();
            if (depth > 0 && recognised(dir, importers)) {
                found.add(dir);
                continue;
            }
            if (depth >= MAX_DEPTH) {
                continue;
            }
            try (Stream<Path> children = Files.list(dir)) {
                for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                    String name = child.getFileName().toString();
                    if (name.startsWith(".") || NOT_PROJECTS.contains(name)) {
                        continue;
                    }
                    queue.add(child);
                    depths.add(depth + 1);
                }
            } catch (IOException | RuntimeException e) {
                // A folder that cannot be listed holds nothing to load.
            }
        }
        return found;
    }

    private static boolean recognised(Path dir, List<ProjectImporter> importers) {
        for (ProjectImporter importer : importers) {
            try {
                if (importer.detects(dir)) {
                    return true;
                }
            } catch (RuntimeException e) {
                // One importer's trouble is not a reason to stop looking.
            }
        }
        return false;
    }
}
