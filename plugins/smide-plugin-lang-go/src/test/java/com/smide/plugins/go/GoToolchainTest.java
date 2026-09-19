package com.smide.plugins.go;

import com.smide.api.lang.Toolchain.Download;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoToolchainTest {

    /** The shape of https://go.dev/dl/?mode=json&include=all, trimmed: a release candidate first, as it can be. */
    private static final String RELEASES = """
            [
              {"version": "go1.26rc1", "stable": false, "files": [
                {"filename": "go1.26rc1.windows-amd64.zip", "os": "windows", "arch": "amd64", "kind": "archive",
                 "sha256": "rc", "size": 1}]},
              {"version": "go1.25.1", "stable": true, "files": [
                {"filename": "go1.25.1.src.tar.gz", "os": "", "arch": "", "kind": "source", "sha256": "src", "size": 2},
                {"filename": "go1.25.1.windows-amd64.msi", "os": "windows", "arch": "amd64", "kind": "installer",
                 "sha256": "msi", "size": 3},
                {"filename": "go1.25.1.windows-amd64.zip", "os": "windows", "arch": "amd64", "kind": "archive",
                 "sha256": "winzip", "size": 78000000},
                {"filename": "go1.25.1.linux-arm64.tar.gz", "os": "linux", "arch": "arm64", "kind": "archive",
                 "sha256": "linuxarm", "size": 70000000}]},
              {"version": "go1.24.7", "stable": true, "files": [
                {"filename": "go1.24.7.darwin-arm64.tar.gz", "os": "darwin", "arch": "arm64", "kind": "archive",
                 "sha256": "old", "size": 5}]}
            ]
            """;

    @Test
    void picksTheNewestStableArchiveNotTheInstaller() {
        Optional<Download> d = GoToolchain.pick(RELEASES, "windows", "amd64");
        assertTrue(d.isPresent());
        assertEquals("1.25.1", d.get().version());
        assertEquals("https://go.dev/dl/go1.25.1.windows-amd64.zip", d.get().url());
        assertEquals("winzip", d.get().sha256());
        assertEquals(78000000, d.get().size());
        assertEquals("go.dev", d.get().source());
    }

    @Test
    void picksThisMachinesProcessor() {
        assertEquals("linuxarm", GoToolchain.pick(RELEASES, "linux", "arm64").orElseThrow().sha256());
    }

    @Test
    void fallsBackToAnOlderStableReleaseWhenTheNewestHasNothingForThisMachine() {
        assertEquals("1.24.7", GoToolchain.pick(RELEASES, "darwin", "arm64").orElseThrow().version());
    }

    @Test
    void nothingForAMachineGoDoesNotBuildFor() {
        assertTrue(GoToolchain.pick(RELEASES, "other", "other").isEmpty());
    }
}
