package com.smide.plugins.rust;

import com.smide.api.problems.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A crate named in Cargo.toml that Cargo has not got. */
class CargoDependenciesTest {

    private static final String MANIFEST = """
            [package]
            name = "tool"
            version = "0.1.0"

            [dependencies]
            serde = { version = "1.0", features = ["derive"] }
            tokoi = "1.35"
            shared = { path = "../shared" }

            [dev-dependencies]
            criterion = "0.5"
            """;

    @Test
    void theMisspeltCrateIsReported(@TempDir Path root) throws IOException {
        Path registry = registryWith(root, "serde-1.0.197", "criterion-0.5.1");
        Path file = root.resolve("Cargo.toml");
        Files.writeString(file, MANIFEST);

        List<Diagnostic> problems = CargoDependencies.problemsIn(file, MANIFEST, registry);

        assertEquals(1, problems.size(), () -> "reported: " + problems);
        assertTrue(problems.get(0).message().contains("tokoi"), problems.get(0).message());
        assertEquals(Diagnostic.UNRESOLVED, problems.get(0).code());
        assertEquals(6, problems.get(0).startLine());
    }

    @Test
    void andNothingIsSaidWhenCargoHasFetchedNothing(@TempDir Path root) {
        assertTrue(CargoDependencies.problemsIn(root.resolve("Cargo.toml"), MANIFEST, null).isEmpty());
    }

    /** A registry holding the crates named, as Cargo unpacks them. */
    private static Path registryWith(Path root, String... crates) throws IOException {
        Path source = root.resolve("cargo/registry/src/index.crates.io-1949cf8c6b5b557f");
        Files.createDirectories(source);
        for (String crate : crates) {
            Files.createDirectories(source.resolve(crate));
        }
        return root.resolve("cargo/registry");
    }
}
