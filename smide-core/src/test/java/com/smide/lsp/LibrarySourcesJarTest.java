package com.smide.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A library jar's coordinates and sources, from where Gradle or Maven keeps it. */
class LibrarySourcesJarTest {

    @Test
    void aJarInGradlesCache(@TempDir Path home) throws IOException {
        Path version = home.resolve(".gradle/caches/modules-2/files-2.1/org.springframework/spring-context/6.1.14");
        Path jar = version.resolve("a1b2c3/spring-context-6.1.14.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        LibrarySources.Artifact artifact = LibrarySources.artifactOfJar(jar).orElseThrow();
        assertEquals("org.springframework:spring-context:6.1.14", artifact.label());

        // Gradle keeps each file of a version in a folder named by its hash; the sources in another.
        assertTrue(LibrarySources.sourcesOnDisk(jar, artifact).isEmpty()
                || !LibrarySources.sourcesOnDisk(jar, artifact).get().startsWith(home));
        Path sources = version.resolve("d4e5f6/spring-context-6.1.14-sources.jar");
        Files.createDirectories(sources.getParent());
        Files.writeString(sources, "sources");
        assertEquals(sources, LibrarySources.sourcesOnDisk(jar, artifact).orElseThrow());
    }

    @Test
    void aJarInMavensRepository(@TempDir Path home) throws IOException {
        Path jar = home.resolve(".m2/repository/org/springframework/spring-context/6.1.14/spring-context-6.1.14.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        LibrarySources.Artifact artifact = LibrarySources.artifactOfJar(jar).orElseThrow();
        assertEquals("org.springframework:spring-context:6.1.14", artifact.label());
        Path sources = jar.resolveSibling("spring-context-6.1.14-sources.jar");
        Files.writeString(sources, "sources");
        assertEquals(sources, LibrarySources.sourcesOnDisk(jar, artifact).orElseThrow());
    }

    @Test
    void notAJarFromNowhereInParticular(@TempDir Path home) {
        assertTrue(LibrarySources.artifactOfJar(home.resolve("lib/whatever.jar")).isEmpty());
    }
}
