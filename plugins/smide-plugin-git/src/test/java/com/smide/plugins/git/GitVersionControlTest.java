package com.smide.plugins.git;

import com.smide.api.vcs.FileStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Git tells the IDE about a file: how it stands, and what the last commit has. */
class GitVersionControlTest {

    @Test
    void whatEachFileIs(@TempDir Path root) throws Exception {
        GitService git = new GitService();
        GitVersionControl vcs = new GitVersionControl(git);
        Path tracked = root.resolve("Kept.java");
        Path changed = root.resolve("Changed.java");
        Files.writeString(tracked, "class Kept {}\n");
        Files.writeString(changed, "class Changed {\n}\n");
        git.init(root);
        git.stage(root, java.util.List.of("Kept.java", "Changed.java"));
        git.commit(root, "first", false);

        Files.writeString(changed, "class Changed {\n    int added;\n}\n");
        Path untracked = root.resolve("Draft.java");
        Files.writeString(untracked, "class Draft {}\n");

        assertTrue(vcs.handles(tracked));
        assertEquals(FileStatus.UNCHANGED, vcs.statusOf(tracked));
        assertEquals(FileStatus.MODIFIED, vcs.statusOf(changed));
        assertEquals(FileStatus.ADDED, vcs.statusOf(untracked));

        // The committed version, which is what the marks beside the lines are compared against.
        assertEquals("class Changed {\n}\n", vcs.committedText(changed).orElseThrow());
        assertTrue(vcs.committedText(untracked).isEmpty(), "a new file has no committed version");
        git.close();
    }

    @Test
    void aFileOutsideAnyRepositoryIsNotItsBusiness(@TempDir Path root) throws IOException {
        GitService git = new GitService();
        GitVersionControl vcs = new GitVersionControl(git);
        Path loose = root.resolve("Loose.java");
        Files.writeString(loose, "class Loose {}\n");

        assertFalse(vcs.handles(loose));
        assertEquals(FileStatus.UNKNOWN, vcs.statusOf(loose));
        assertTrue(vcs.committedText(loose).isEmpty());
        git.close();
    }
}
