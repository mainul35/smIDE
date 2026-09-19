package com.smide.plugins.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LombokTest {

    @Test
    void aProjectUsesLombokWhenABuildFileSaysSoEvenAFewFoldersIn(@TempDir Path root) throws IOException {
        Path build = root.resolve("POISYA-administration/poisya/build.gradle");
        Files.createDirectories(build.getParent());
        Files.writeString(build, "dependencies {\n  compileOnly 'org.projectlombok:lombok:1.18.42'\n}\n");
        assertTrue(Lombok.usedBy(root));
    }

    @Test
    void notWhenNoBuildMentionsIt(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project><dependencies/></project>");
        Files.createDirectories(root.resolve("node_modules/x"));
        Files.writeString(root.resolve("node_modules/x/pom.xml"), "lombok");
        assertFalse(Lombok.usedBy(root));
    }

    @Test
    void theNewestJarWins() {
        List<Path> jars = List.of(Path.of("a/lombok-1.18.32.jar"), Path.of("b/lombok-1.18.46.jar"), Path.of("c/lombok-1.18.9.jar"));
        assertEquals(Path.of("b/lombok-1.18.46.jar"), Lombok.newest(jars).orElseThrow());
        assertTrue(Lombok.compare(Lombok.version(Path.of("lombok-1.18.30.jar")), Lombok.OLDEST) >= 0);
        assertTrue(Lombok.compare(Lombok.version(Path.of("lombok-1.18.24.jar")), Lombok.OLDEST) < 0);
    }
}
