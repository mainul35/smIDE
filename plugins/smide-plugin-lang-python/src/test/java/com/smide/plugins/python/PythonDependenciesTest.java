package com.smide.plugins.python;

import com.smide.api.problems.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A requirement that is not installed in the project's own environment. */
class PythonDependenciesTest {

    @Test
    void theMisspeltRequirementIsReported(@TempDir Path root) throws IOException {
        environmentWith(root, "requests-2.31.0.dist-info", "flask_sqlalchemy", "httpx");
        String text = """
                requests>=2.31
                reqests==2.31.0
                Flask-SQLAlchemy
                httpx[http2]>=0.27 ; python_version >= "3.9"
                """;
        Path file = root.resolve("requirements.txt");
        Files.writeString(file, text);

        List<Diagnostic> problems = PythonDependencies.problemsIn(file, text);

        assertEquals(1, problems.size(), () -> "reported: " + problems);
        assertTrue(problems.get(0).message().contains("reqests"), problems.get(0).message());
        assertEquals(1, problems.get(0).startLine());
    }

    @Test
    void pyprojectsListIsReadToo(@TempDir Path root) throws IOException {
        environmentWith(root, "httpx");
        String text = """
                [project]
                name = "reports"
                dependencies = ["httpx>=0.27", "pandsa>=2.0"]
                """;
        Path file = root.resolve("pyproject.toml");
        Files.writeString(file, text);

        List<Diagnostic> problems = PythonDependencies.problemsIn(file, text);

        assertEquals(1, problems.size(), () -> "reported: " + problems);
        assertTrue(problems.get(0).message().contains("pandsa"), problems.get(0).message());
    }

    @Test
    void andNothingIsSaidWithoutAnEnvironmentOfItsOwn(@TempDir Path root) throws IOException {
        String text = "reqests==2.31.0\n";
        Path file = root.resolve("requirements.txt");
        Files.writeString(file, text);

        assertTrue(PythonDependencies.problemsIn(file, text).isEmpty());
    }

    /** A .venv holding the packages named, as pip installs them. */
    private static void environmentWith(Path root, String... installed) throws IOException {
        Path packages = root.resolve(".venv/lib/python3.12/site-packages");
        Files.createDirectories(packages);
        for (String name : installed) {
            Files.createDirectories(packages.resolve(name));
        }
    }
}
