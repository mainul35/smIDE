package com.smide.plugins.web;

import com.smide.api.project.ProjectModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A package.json project: its scripts as tasks, its workspace packages as modules. */
class NpmImporterTest {

    @Test
    void scriptsAndWorkspacePackages(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), """
                { "name": "site", "workspaces": ["packages/*"],
                  "scripts": { "dev": "vite", "build": "vite build" } }""");
        Files.createDirectories(root.resolve("packages/ui"));
        Files.writeString(root.resolve("packages/ui/package.json"),
                "{ \"name\": \"@site/ui\", \"scripts\": { \"build\": \"tsc\" } }");
        Files.createDirectories(root.resolve("src"));

        ProjectModel model = new NpmImporter().importProject(null, workspace(root), root);
        assertEquals("npm", model.type());
        assertEquals("site", model.name());
        assertEquals(List.of("site", "@site/ui"), model.modules().stream().map(ProjectModel.ProjectModule::name).toList());
        assertTrue(model.tasks().stream().anyMatch(t -> t.command().equals(List.of("npm", "run", "dev"))), "npm run dev");
        assertTrue(model.tasks().stream().anyMatch(t -> t.command().equals(List.of("npm", "run", "build"))
                && t.workingDir().endsWith("ui")), "the package's own build");
        assertTrue(model.tasks().stream().anyMatch(t -> t.command().equals(List.of("npm", "install"))), "install");
    }

    @Test
    void theManagerIsTheOneTheLockFileNames(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), "{ \"name\": \"site\", \"scripts\": { \"dev\": \"vite\" } }");
        assertEquals("npm", NpmImporter.manager(root));
        Files.writeString(root.resolve("pnpm-lock.yaml"), "");
        assertEquals("pnpm", NpmImporter.manager(root));
        ProjectModel model = new NpmImporter().importProject(null, workspace(root), root);
        assertTrue(model.tasks().stream().anyMatch(t -> t.command().equals(List.of("pnpm", "run", "dev"))));
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
