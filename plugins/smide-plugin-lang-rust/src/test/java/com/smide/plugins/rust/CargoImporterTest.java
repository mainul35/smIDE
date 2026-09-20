package com.smide.plugins.rust;

import com.smide.api.project.ProjectModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Cargo crate and the members of a workspace, read from Cargo.toml. */
class CargoImporterTest {

    @Test
    void theCrateAndItsWorkspaceMembers(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("Cargo.toml"),
                "[package]\nname = \"tool\"\nversion = \"0.1.0\"\n\n[workspace]\nmembers = [\"crates/*\", \"extra\"]\n");
        for (String member : List.of("crates/core", "crates/cli", "extra")) {
            Files.createDirectories(root.resolve(member).resolve("src"));
            Files.writeString(root.resolve(member).resolve("Cargo.toml"),
                    "[package]\nname = \"" + member.substring(member.lastIndexOf('/') + 1) + "\"\n");
        }
        ProjectModel model = new CargoImporter().importProject(null, workspace(root), root);
        assertEquals("cargo", model.type());
        assertEquals("tool", model.name());
        assertEquals(List.of("tool", "cli", "core", "extra"),
                model.modules().stream().map(ProjectModel.ProjectModule::name).toList());
        assertTrue(model.tasks().stream().anyMatch(t -> t.command().equals(List.of("cargo", "test"))));
    }

    @Test
    void aCrateOnItsOwn(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("Cargo.toml"), "[package]\nname = \"lonely\"\n");
        ProjectModel model = new CargoImporter().importProject(null, workspace(root), root);
        assertEquals(List.of("lonely"), model.modules().stream().map(ProjectModel.ProjectModule::name).toList());
    }

/** A workspace that is only its root, which is all an importer asks of it. */
private static com.smide.api.workspace.Workspace workspace(java.nio.file.Path root) {
    return (com.smide.api.workspace.Workspace) java.lang.reflect.Proxy.newProxyInstance(
            com.smide.api.workspace.Workspace.class.getClassLoader(),
            new Class<?>[] {com.smide.api.workspace.Workspace.class},
            (proxy, method, args) -> "root".equals(method.getName()) ? root
                    : "name".equals(method.getName()) ? String.valueOf(root.getFileName()) : null);
}
}
