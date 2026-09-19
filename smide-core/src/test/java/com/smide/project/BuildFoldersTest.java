package com.smide.project;

import com.smide.api.Ide;
import com.smide.api.project.ProjectImporter;
import com.smide.api.project.ProjectModel;
import com.smide.api.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a build is found when the workspace's own root has none. */
class BuildFoldersTest {

    private static final ProjectImporter GRADLE = new ProjectImporter() {
        @Override
        public String id() {
            return "gradle";
        }

        @Override
        public boolean detects(Path root) {
            return Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("settings.gradle"));
        }

        @Override
        public ProjectModel importProject(Ide ide, Workspace workspace) {
            return null;
        }
    };

    private static void file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
    }

    @Test
    void aBuildTwoFoldersDownIsFound(@TempDir Path root) throws IOException {
        // The shape of POISYA-SAAS-E-commerce: the Gradle build two levels in.
        Files.createDirectories(root.resolve("analysis"));
        Files.createDirectories(root.resolve(".idea"));
        file(root.resolve("POISYA-administration/poisya/settings.gradle"));
        file(root.resolve("POISYA-administration/poisya/build.gradle"));
        file(root.resolve("POISYA-administration/poisya/app/build.gradle"));
        assertEquals(List.of(root.resolve("POISYA-administration/poisya")), BuildFolders.below(root, List.of(GRADLE)));
    }

    @Test
    void shallowestFirstAndNothingFromOutputOrHiddenFolders(@TempDir Path root) throws IOException {
        file(root.resolve("deep/er/still/build.gradle"));
        file(root.resolve("backend/build.gradle"));
        file(root.resolve("node_modules/pkg/build.gradle"));
        file(root.resolve(".cache/x/build.gradle"));
        file(root.resolve("build/tmp/build.gradle"));
        assertEquals(List.of(root.resolve("backend"), root.resolve("deep/er/still")), BuildFolders.below(root, List.of(GRADLE)));
    }

    @Test
    void notTooDeep(@TempDir Path root) throws IOException {
        file(root.resolve("a/b/c/d/e/build.gradle"));
        assertEquals(List.of(), BuildFolders.below(root, List.of(GRADLE)));
    }
}
