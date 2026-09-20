package com.smide.plugins.web;

import com.smide.api.problems.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A package named in package.json that is not installed. */
class NpmDependenciesTest {

    private static final String PACKAGE_JSON = """
            {
              "name": "site",
              "dependencies": {
                "react": "^18.2.0",
                "reactt": "^18.2.0",
                "@scope/ui": "1.0.0",
                "local-thing": "file:../local-thing"
              },
              "devDependencies": {
                "vite": "^5.0.0"
              }
            }
            """;

    @Test
    void theOneThatIsNotInstalledIsReported(@TempDir Path root) throws IOException {
        for (String installed : List.of("react", "@scope/ui", "vite")) {
            Files.createDirectories(root.resolve("node_modules").resolve(installed));
        }
        Path file = root.resolve("package.json");
        Files.writeString(file, PACKAGE_JSON);

        List<Diagnostic> problems = NpmDependencies.problemsIn(file, PACKAGE_JSON);

        assertEquals(1, problems.size(), () -> "reported: " + problems);
        assertTrue(problems.get(0).message().contains("reactt"), problems.get(0).message());
        assertEquals(Diagnostic.UNRESOLVED, problems.get(0).code());
        assertEquals(4, problems.get(0).startLine());
    }

    @Test
    void andNothingIsSaidBeforeAnythingIsInstalled(@TempDir Path root) throws IOException {
        Path file = root.resolve("package.json");
        Files.writeString(file, PACKAGE_JSON);

        assertTrue(NpmDependencies.problemsIn(file, PACKAGE_JSON).isEmpty());
    }
}
