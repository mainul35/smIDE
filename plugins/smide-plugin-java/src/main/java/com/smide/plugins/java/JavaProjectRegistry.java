package com.smide.plugins.java;

import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The Java-specific project information per workspace root, filled by the importers. */
public final class JavaProjectRegistry {

    private final Map<Path, JavaProjectInfo> byRoot = new ConcurrentHashMap<>();

    /**
     * Keeps what the project's own build said, not what a build inside it said.
     *
     * <p>Every build in a project is imported now, and a repository often holds a small one
     * beside the real thing: a sample, a fixture, a demo with a pom of its own. Each import
     * lands here under the same workspace root, so the last one won - and in smIDE's own
     * repository that was {@code samples/review-playground}, after which the IDE believed the
     * project was a two-class sample: no main classes to detect, no run configuration offered,
     * and a run that assembled its class path from the wrong module. The outermost build is the
     * project's; one further in is a part of it and does not replace it.
     */
    public void put(Path root, JavaProjectInfo info) {
        Path key = root.toAbsolutePath().normalize();
        JavaProjectInfo existing = byRoot.get(key);
        if (existing != null && depth(key, info) > depth(key, existing)) {
            return;
        }
        byRoot.put(key, info);
    }

    /** How far inside the workspace a build is: the root's own build is nearest. */
    private static int depth(Path workspaceRoot, JavaProjectInfo info) {
        Path buildRoot = info.buildRoot();
        if (buildRoot == null) {
            return Integer.MAX_VALUE;
        }
        try {
            Path relative = workspaceRoot.relativize(buildRoot.toAbsolutePath().normalize());
            return relative.toString().isEmpty() ? 0 : relative.getNameCount();
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    public Optional<JavaProjectInfo> get(Workspace workspace) {
        return workspace == null ? Optional.empty() : Optional.ofNullable(byRoot.get(workspace.root()));
    }

    /** The release a workspace's build compiles for, or 0 when it does not say. */
    public int requestedRelease(Workspace workspace) {
        return get(workspace).map(JavaProjectInfo::javaVersion).orElse(0);
    }

    public void remove(Workspace workspace) {
        byRoot.remove(workspace.root());
    }
}
