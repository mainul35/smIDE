package com.smide.plugins.python;

import com.smide.api.project.ProjectModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Python project, and the tool it is kept with. */
class PythonImporterTest {

    @Test
    void aProjectKeptWithUv(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"reports\"\n\n[tool.uv]\n");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("tests"));
        ProjectModel model = new PythonImporter().importProject(null, workspace(root), root);
        assertEquals("python", model.type());
        assertEquals("reports", model.name());
        assertEquals(List.of(root.resolve("src")), model.modules().get(0).sourceRoots());
        assertEquals(List.of(root.resolve("tests")), model.modules().get(0).testRoots());
        assertTrue(model.tasks().stream().anyMatch(t -> t.command().equals(List.of("uv", "sync"))));
    }

    @Test
    void oneKeptWithRequirements(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("requirements.txt"), "requests\n");
        assertEquals("pip", PythonImporter.manager(root, ""));
        ProjectModel model = new PythonImporter().importProject(null, workspace(root), root);
        assertTrue(model.tasks().stream()
                .anyMatch(t -> t.command().equals(List.of("pip", "install", "-r", "requirements.txt"))));
        assertTrue(new PythonImporter().detects(root));
    }

    /** A workspace that is only its root, which is all an importer asks of it. */
    private static com.smide.api.workspace.Workspace workspace(Path root) {
        return (com.smide.api.workspace.Workspace) java.lang.reflect.Proxy.newProxyInstance(
                com.smide.api.workspace.Workspace.class.getClassLoader(),
                new Class<?>[] {com.smide.api.workspace.Workspace.class},
                (proxy, method, args) -> "root".equals(method.getName()) ? root
                        : "name".equals(method.getName()) ? String.valueOf(root.getFileName()) : null);
    }
}
