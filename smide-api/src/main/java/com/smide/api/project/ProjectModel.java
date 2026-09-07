package com.smide.api.project;

import java.nio.file.Path;
import java.util.List;

/**
 * A build system's description of a workspace: modules, source roots, tasks.
 *
 * @param type    e.g. {@code maven}, {@code gradle}, {@code plain}
 * @param name    the project's own name
 * @param root    the workspace root
 * @param modules at least one; a single-module project has one whose root is {@code root}
 * @param tasks   things the build can do, for the build tool window
 */
public record ProjectModel(String type, String name, Path root, List<ProjectModule> modules, List<BuildTask> tasks) {

    public record ProjectModule(String name,
                                Path root,
                                List<Path> sourceRoots,
                                List<Path> testRoots,
                                List<Path> resourceRoots,
                                Path outputDir) {
    }

    /**
     * @param command the process to run, from the module root
     * @param group   how the tool window groups it: {@code Lifecycle}, {@code Plugins}, ...
     */
    public record BuildTask(String name, String description, String group, List<String> command, Path workingDir) {
    }

    /** True when {@code file} lies under a source, test or resource root. */
    public boolean isSource(Path file) {
        for (ProjectModule module : modules) {
            for (List<Path> roots : List.of(module.sourceRoots(), module.testRoots(), module.resourceRoots())) {
                for (Path root : roots) {
                    if (file.startsWith(root)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
