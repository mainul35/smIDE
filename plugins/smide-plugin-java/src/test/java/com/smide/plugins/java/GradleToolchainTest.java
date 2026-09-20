package com.smide.plugins.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Gradle project with a wrapper that cannot run needs a Gradle of the IDE's own. */
class GradleToolchainTest {

    private static Path build(Path root, String wrapperJar) throws IOException {
        Path dir = Files.createDirectories(root.resolve("POISYA-administration/poisya"));
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'poisya'");
        Files.writeString(dir.resolve(JavaTools.WINDOWS ? "gradlew.bat" : "gradlew"), "#!/bin/sh");
        Path properties = Files.createDirectories(dir.resolve("gradle/wrapper")).resolve("gradle-wrapper.properties");
        // As Gradle writes it, with the colon escaped the way a properties file escapes one.
        Files.writeString(properties, "distributionUrl=https"
                + "\\" + "://services.gradle.org/distributions/gradle-8.10.2-bin.zip\n");
        if (wrapperJar != null) {
            Files.writeString(dir.resolve("gradle/wrapper").resolve(wrapperJar), "jar");
        }
        return dir;
    }

    @Test
    void neededWhenTheWrappersJarIsMissing(@TempDir Path root) throws IOException {
        Path dir = build(root, null);
        assertNull(JavaTools.usableGradleWrapper(dir), "the wrapper cannot run");
        assertTrue(new GradleToolchain().isNeededBy(root), "even with the build two folders in");
    }

    @Test
    void notNeededWhenTheWrapperCanRun(@TempDir Path root) throws IOException {
        Path dir = build(root, "gradle-wrapper.jar");
        assertNotNull(JavaTools.usableGradleWrapper(dir));
        assertFalse(new GradleToolchain().isNeededBy(root));
    }

    @Test
    void theVersionTheWrapperAsksFor(@TempDir Path root) throws IOException {
        assertEquals("8.10.2", GradleToolchain.versionInWrapper(build(root, null)).orElseThrow());
    }
}
