package com.smide.plugins.java;

import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The Java-specific project information per workspace root, filled by the importers. */
public final class JavaProjectRegistry {

    private final Map<Path, JavaProjectInfo> byRoot = new ConcurrentHashMap<>();

    public void put(Path root, JavaProjectInfo info) {
        byRoot.put(root.toAbsolutePath().normalize(), info);
    }

    public Optional<JavaProjectInfo> get(Workspace workspace) {
        return workspace == null ? Optional.empty() : Optional.ofNullable(byRoot.get(workspace.root()));
    }

    public void remove(Workspace workspace) {
        byRoot.remove(workspace.root());
    }
}
