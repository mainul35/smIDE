package com.smide.plugins.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NodeToolchainTest {

    /** The shape of https://nodejs.org/dist/index.json: newest first, "lts" false or the line's name. */
    private static final String INDEX = """
            [
              {"version": "v25.1.0", "lts": false},
              {"version": "v24.9.0", "lts": "Krypton"},
              {"version": "v22.20.0", "lts": "Jod"}
            ]
            """;

    private static final String SUMS = """
            aaaa1111  node-v24.9.0-darwin-arm64.tar.gz
            bbbb2222  node-v24.9.0-linux-x64.tar.gz
            cccc3333  node-v24.9.0-win-x64.zip
            dddd4444  node-v24.9.0-win-x64.7z
            """;

    @Test
    void theNewestLongTermSupportReleaseNotTheNewestRelease() {
        assertEquals("v24.9.0", NodeToolchain.newestLts(INDEX));
    }

    @Test
    void namesTheArchiveAsNodejsOrgDoes() {
        assertEquals("node-v24.9.0-win-x64.zip", NodeToolchain.fileName("v24.9.0", "windows", "amd64"));
        assertEquals("node-v24.9.0-linux-x64.tar.gz", NodeToolchain.fileName("v24.9.0", "linux", "amd64"));
        assertEquals("node-v24.9.0-darwin-arm64.tar.gz", NodeToolchain.fileName("v24.9.0", "darwin", "arm64"));
        assertNull(NodeToolchain.fileName("v24.9.0", "other", "amd64"));
    }

    @Test
    void theChecksumOfExactlyThatFile() {
        assertEquals("cccc3333", NodeToolchain.checksum(SUMS, "node-v24.9.0-win-x64.zip"));
        assertEquals("bbbb2222", NodeToolchain.checksum(SUMS, "node-v24.9.0-linux-x64.tar.gz"));
        assertNull(NodeToolchain.checksum(SUMS, "node-v24.9.0-win-arm64.zip"));
    }
}
