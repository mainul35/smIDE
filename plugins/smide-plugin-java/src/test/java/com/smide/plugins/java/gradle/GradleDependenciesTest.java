package com.smide.plugins.java.gradle;

import com.smide.api.problems.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A coordinate Gradle has not got, found where it is written. */
class GradleDependenciesTest {

    private static final String SCRIPT = """
            plugins {
                id 'org.springframework.boot' version '3.2.0'
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter-web'
                annotationProcessor 'org.projectlombok:lombok:1.18.42'
                annotationProcessor 'org.projectlombok:lombo:1.18.42'
                testImplementation "org.junit.jupiter:junit-jupiter:$junitVersion"
                implementation group: 'com.google.guava', name: 'guava', version: '33.0.0'
                implementation project(':shared')
            }
            """;

    @Test
    void theMisspeltOneIsReportedAndTheOthersAreNot(@TempDir Path root) throws IOException {
        Path cache = cacheWith(root, "org.springframework.boot/spring-boot-starter-web/3.2.0",
                "org.projectlombok/lombok/1.18.42", "org.junit.jupiter/junit-jupiter/5.10.0",
                "com.google.guava/guava/33.0.0");
        Path script = root.resolve("build.gradle");
        Files.writeString(script, SCRIPT);

        List<Diagnostic> problems = GradleDependencies.problemsIn(script, SCRIPT, cache, root.resolve("no-maven"));

        assertEquals(1, problems.size(), () -> "reported: " + problems);
        Diagnostic only = problems.get(0);
        assertTrue(only.message().contains("org.projectlombok:lombo:1.18.42"), only.message());
        assertEquals(Diagnostic.Severity.ERROR, only.severity());
        assertEquals(Diagnostic.UNRESOLVED, only.code());
        // On the coordinate itself, the twelfth line of the script, not on the whole line.
        assertEquals(11, only.startLine());
        assertEquals(SCRIPT.lines().toList().get(11).indexOf("org.projectlombok"), only.startColumn());
    }

    @Test
    void aVersionInTheMavenRepositoryCountsToo(@TempDir Path root) throws IOException {
        Path cache = cacheWith(root);
        Path maven = root.resolve("m2");
        Files.createDirectories(maven.resolve("com/example/tool/1.0"));
        String script = "dependencies {\n    implementation 'com.example:tool:1.0'\n}\n";

        assertTrue(GradleDependencies.problemsIn(root.resolve("build.gradle"), script, cache, maven).isEmpty());
    }

    @Test
    void aVersionThatIsNotWrittenOutIsNotJudged(@TempDir Path root) throws IOException {
        Path cache = cacheWith(root, "org.projectlombok/lombok/1.18.42");
        String script = "dependencies {\n    implementation 'org.projectlombok:lombok:${lombokVersion}'\n}\n";

        assertTrue(GradleDependencies.problemsIn(root.resolve("build.gradle"), script, cache, null).isEmpty());
    }

    @Test
    void andNothingIsSaidWhenGradleHasNeverRun(@TempDir Path root) {
        assertTrue(GradleDependencies.problemsIn(root.resolve("build.gradle"), SCRIPT, null, null).isEmpty());
    }

    @Test
    void itIsGradlesScriptsThisReadsAndNotOtherFiles() {
        assertTrue(GradleDependencies.isBuildScript(Path.of("a", "build.gradle")));
        assertTrue(GradleDependencies.isBuildScript(Path.of("a", "build.gradle.kts")));
        assertFalse(GradleDependencies.isBuildScript(Path.of("a", "settings.gradle")));
        assertFalse(GradleDependencies.isBuildScript(Path.of("a", "pom.xml")));
    }

    @Test
    void kotlinsFormIsReadAsWell(@TempDir Path root) throws IOException {
        Path cache = cacheWith(root, "io.ktor/ktor-server-core/2.3.0");
        String script = """
                dependencies {
                    implementation("io.ktor:ktor-server-core:2.3.0")
                    implementation("io.ktor:ktor-server-cor:2.3.0")
                }
                """;

        List<Diagnostic> problems = GradleDependencies.problemsIn(root.resolve("build.gradle.kts"), script, cache, null);

        assertEquals(1, problems.size(), () -> "reported: " + problems);
        assertTrue(problems.get(0).message().contains("ktor-server-cor:"), problems.get(0).message());
    }

    /** A Gradle cache holding the modules named, in Gradle's own layout. */
    private static Path cacheWith(Path root, String... modules) throws IOException {
        Path cache = root.resolve("gradle-cache/modules-2/files-2.1");
        Files.createDirectories(cache);
        for (String module : modules) {
            Files.createDirectories(cache.resolve(module));
        }
        return cache;
    }
}
