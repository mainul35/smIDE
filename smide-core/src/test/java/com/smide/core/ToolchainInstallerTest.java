package com.smide.core;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolchainInstallerTest {

    /** A toolchain whose installation is any folder with a bin/tool in it. */
    private static final Toolchain TOOL = new Toolchain() {
        @Override
        public String id() {
            return "tool";
        }

        @Override
        public String displayName() {
            return "Go toolchain";
        }

        @Override
        public boolean isNeededBy(Path root) {
            return true;
        }

        @Override
        public Optional<Path> locate(Ide ide) {
            return Optional.empty();
        }

        @Override
        public String purpose() {
            return "";
        }

        @Override
        public boolean accepts(Path home) {
            return Files.isRegularFile(home.resolve("bin").resolve("tool"));
        }
    };

    @Test
    void findsTheInstallationInsideTheFolderAnArchiveWrapsItIn(@TempDir Path dir) throws Exception {
        Path wrapped = dir.resolve("node-v24.9.0-linux-x64");
        Files.createDirectories(wrapped.resolve("bin"));
        Files.writeString(wrapped.resolve("bin").resolve("tool"), "");
        Files.createDirectories(dir.resolve("docs"));
        assertEquals(Optional.of(wrapped), ToolchainInstaller.home(TOOL, dir));
    }

    @Test
    void orTheFolderItselfWhenTheArchiveIsFlat(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("bin").resolve("tool"), "");
        assertEquals(Optional.of(dir), ToolchainInstaller.home(TOOL, dir));
    }

    @Test
    void nothingWhenWhatWasUnpackedIsNotAnInstallation(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("something").resolve("else"));
        assertTrue(ToolchainInstaller.home(TOOL, dir).isEmpty());
    }

    @Test
    void checksumsAsPublishersWriteThem(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.write(file, "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ToolchainInstaller.sha256(file));
    }

    @Test
    void namesAndSizesAsTheQuestionReadsThem() {
        assertEquals("Go", ToolchainInstaller.shortName(TOOL));
        assertEquals("24.9.0", ToolchainInstaller.folderName("v24.9.0"));
        assertEquals("1.26_rc1", ToolchainInstaller.folderName("1.26 rc1"));
        assertEquals("78 MB", ToolchainInstaller.size(78_000_000));
        assertEquals("512 KB", ToolchainInstaller.size(512_000));
    }

    @Test
    void progressSaysWhatIsHappeningNotWhichFileIsWritten() {
        assertEquals("Downloading Python 3.14.7 (3.2 MB of 21.7 MB)", ToolchainInstaller.describe(
                "Downloading Python 3.14.7", "Downloading 3.14.7.download.tar.gz (3.2 MB of 21.7 MB)"));
        assertEquals("Unpacking Go 1.25.1: fmt.go", ToolchainInstaller.describe(
                "Unpacking Go 1.25.1", "Extracting go\\src\\fmt\\fmt.go"));
        assertEquals("Checking", ToolchainInstaller.describe("Checking", ""));
    }
}
