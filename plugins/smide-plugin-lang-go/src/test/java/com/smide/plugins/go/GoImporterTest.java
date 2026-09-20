package com.smide.plugins.go;

import com.smide.api.project.ProjectModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Go module, and the modules a go.work uses. */
class GoImporterTest {

    @Test
    void oneModule(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("go.mod"), "module github.com/demo/service\n\ngo 1.22\n");
        ProjectModel model = new GoImporter().importProject(null, workspace(root), root);
        assertEquals("go", model.type());
        assertEquals(List.of("service"), model.modules().stream().map(ProjectModel.ProjectModule::name).toList());
        assertTrue(model.tasks().stream().anyMatch(t -> t.command().equals(List.of("go", "test", "./..."))));
    }

    @Test
    void theModulesOfAWorkspace(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("go.work"), "go 1.22\n\nuse (\n    ./api\n    ./worker\n)\n");
        for (String member : List.of("api", "worker")) {
            Files.createDirectories(root.resolve(member));
            Files.writeString(root.resolve(member).resolve("go.mod"), "module github.com/demo/" + member + "\n");
        }
        ProjectModel model = new GoImporter().importProject(null, workspace(root), root);
        assertEquals(List.of("api", "worker"), model.modules().stream().map(ProjectModel.ProjectModule::name).toList());
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
