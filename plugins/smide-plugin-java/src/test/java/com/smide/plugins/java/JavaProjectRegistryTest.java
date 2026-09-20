package com.smide.plugins.java;

import com.smide.api.project.ProjectModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The project's own build, not a sample with a pom of its own that happens to be inside it. */
class JavaProjectRegistryTest {

    @Test
    void aBuildInsideTheProjectDoesNotReplaceTheProjectsOwn(@TempDir Path root) {
        JavaProjectRegistry registry = new JavaProjectRegistry();
        registry.put(root, info(root, "smide-parent"));
        registry.put(root, info(root.resolve("samples/review-playground"), "review-playground"));

        assertEquals("smide-parent", registry.get(workspace(root)).orElseThrow().artifactId());
    }

    @Test
    void andTheProjectsOwnStillReplacesItself(@TempDir Path root) {
        JavaProjectRegistry registry = new JavaProjectRegistry();
        registry.put(root, info(root, "before"));
        registry.put(root, info(root, "after"));

        assertEquals("after", registry.get(workspace(root)).orElseThrow().artifactId());
    }

    /** A build two folders in is all there is, so it is the project's: the Gradle case. */
    @Test
    void aBuildInsideItIsUsedWhenItIsTheOnlyOne(@TempDir Path root) {
        JavaProjectRegistry registry = new JavaProjectRegistry();
        registry.put(root, info(root.resolve("POISYA-administration/poisya"), "poisya"));

        assertEquals("poisya", registry.get(workspace(root)).orElseThrow().artifactId());
    }

    private static JavaProjectInfo info(Path buildRoot, String artifactId) {
        ProjectModel model = new ProjectModel("maven", artifactId, buildRoot, List.of(), List.of());
        return new JavaProjectInfo("maven", model, Set.of(), List.of(), List.of(),
                "jar", artifactId, "1.0", List.of(), List.of(), 21);
    }

    /** A workspace that is only its root, which is all the registry asks of it. */
    private static com.smide.api.workspace.Workspace workspace(Path root) {
        return (com.smide.api.workspace.Workspace) java.lang.reflect.Proxy.newProxyInstance(
                com.smide.api.workspace.Workspace.class.getClassLoader(),
                new Class<?>[] {com.smide.api.workspace.Workspace.class},
                (proxy, method, args) -> "root".equals(method.getName()) ? root : null);
    }
}
