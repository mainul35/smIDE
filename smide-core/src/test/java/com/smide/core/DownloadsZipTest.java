package com.smide.core;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the IDE unpacks stays runnable: a tool's launcher out of a zip - Gradle's bin/gradle -
 * is a program, not a file that cannot be started.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class DownloadsZipTest {

    private static Path zipWith(Path dir) throws IOException {
        Path zip = dir.resolve("tool.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(Files.newOutputStream(zip))) {
            ZipArchiveEntry program = new ZipArchiveEntry("tool-1.0/bin/tool");
            program.setUnixMode(0755);
            out.putArchiveEntry(program);
            out.write("#!/bin/sh\necho hello\n".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
            ZipArchiveEntry readme = new ZipArchiveEntry("tool-1.0/README.txt");
            readme.setUnixMode(0644);
            out.putArchiveEntry(readme);
            out.write("read me\n".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
        return zip;
    }

    @Test
    void aZipsExecuteBitsSurviveUnpacking(@TempDir Path dir) throws IOException {
        Path into = dir.resolve("unpacked");
        new DownloadsImpl(dir).extract(zipWith(dir), into, (message, fraction) -> {
        });
        assertTrue(Files.isExecutable(into.resolve("tool-1.0/bin/tool")), "the launcher can be run");
        assertFalse(Files.isExecutable(into.resolve("tool-1.0/README.txt")), "and a plain file is not made one");
    }

    @Test
    void aLauncherThatLostThemIsRepaired(@TempDir Path dir) throws IOException {
        Path into = dir.resolve("unpacked");
        new DownloadsImpl(dir).extract(zipWith(dir), into, (message, fraction) -> {
        });
        Path tool = into.resolve("tool-1.0/bin/tool");
        // As a zip written without permissions leaves it, and as the IDE's own copy was.
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rw-r--r--"));
        ToolchainInstaller.makeRunnable(into);
        assertTrue(Files.isExecutable(tool));
    }
}
