package com.smide.plugins.python;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonToolchainTest {

    private static final String BASE = "https://github.com/astral-sh/python-build-standalone/releases/download/20260901/";

    /** The shape of a python-build-standalone release: every Python at once, pre-releases and variants beside them. */
    private static final String RELEASE = """
            {"assets": [
              {"name": "cpython-3.15.0a3+20260901-x86_64-pc-windows-msvc-install_only_stripped.tar.gz",
               "browser_download_url": "%1$scpython-3.15.0a3.tar.gz", "size": 1, "digest": "sha256:alpha"},
              {"name": "cpython-3.14.7+20260901-x86_64-pc-windows-msvc-install_only.tar.gz",
               "browser_download_url": "%1$sfull.tar.gz", "size": 60000000, "digest": "sha256:full"},
              {"name": "cpython-3.14.7+20260901-x86_64-pc-windows-msvc-install_only_stripped.tar.gz",
               "browser_download_url": "%1$sstripped.tar.gz", "size": 22000000, "digest": "sha256:stripped"},
              {"name": "cpython-3.14.7+20260901-x86_64-pc-windows-msvc-freethreaded+pgo-full.tar.zst",
               "browser_download_url": "%1$sft.tar.zst", "size": 3, "digest": "sha256:ft"},
              {"name": "cpython-3.13.9+20260901-x86_64-pc-windows-msvc-install_only_stripped.tar.gz",
               "browser_download_url": "%1$solder.tar.gz", "size": 4, "digest": "sha256:older"},
              {"name": "cpython-3.9.24+20260901-aarch64-apple-darwin-install_only.tar.gz",
               "browser_download_url": "%1$smac.tar.gz", "size": 5},
              {"name": "SHA256SUMS", "browser_download_url": "%1$sSHA256SUMS", "size": 6}
            ]}
            """.formatted(BASE);

    @Test
    void theNewestStableStrippedInstallOnlyBuild() {
        PythonToolchain.Asset a = PythonToolchain.pick(RELEASE, "windows", "amd64").orElseThrow();
        assertEquals("3.14.7", a.version());
        assertEquals(BASE + "stripped.tar.gz", a.url());
        assertEquals("stripped", a.sha256());
        assertEquals(22000000, a.size());
    }

    @Test
    void noDigestMeansTheChecksumComesFromTheSumsFile() {
        PythonToolchain.Asset a = PythonToolchain.pick(RELEASE, "darwin", "arm64").orElseThrow();
        assertEquals("3.9.24", a.version());
        assertNull(a.sha256());
        String sums = "eeee5555  cpython-3.9.24+20260901-aarch64-apple-darwin-install_only.tar.gz\n";
        assertEquals("eeee5555", PythonToolchain.checksum(sums, a.name()));
    }

    @Test
    void nothingForAMachineWithNoBuild() {
        assertTrue(PythonToolchain.pick(RELEASE, "linux", "amd64").isEmpty());
        assertNull(PythonToolchain.triple("other", "amd64"));
        assertEquals("aarch64-unknown-linux-gnu", PythonToolchain.triple("linux", "arm64"));
    }
}
