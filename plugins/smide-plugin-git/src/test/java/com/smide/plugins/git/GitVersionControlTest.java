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
    void committingOneChangeCommitsThatChangeAndNothingElse(@TempDir Path root) throws Exception {
        GitService git = new GitService();
        GitVersionControl vcs = new GitVersionControl(git);
        Path file = root.resolve("Order.java");
        Path other = root.resolve("Other.java");
        Files.writeString(file, "one\ntwo\nthree\n");
        Files.writeString(other, "untouched\n");
        git.init(root);
        git.stage(root, java.util.List.of("Order.java", "Other.java"));
        git.commit(root, "first", false);

        // Two changes in the working tree; only the first is committed.
        Files.writeString(file, "ONE\ntwo\nTHREE\n");
        vcs.commitContent(file, "ONE\ntwo\nthree\n", "first line only");

        assertEquals("ONE\ntwo\nthree\n", vcs.committedText(file).orElseThrow(),
                "the commit has the first change and not the second");
        assertEquals("ONE\ntwo\nTHREE\n", Files.readString(file), "the working tree is untouched");
        assertEquals(FileStatus.MODIFIED, vcs.statusOf(file), "the second change is still uncommitted");
        assertEquals("untouched\n", vcs.committedText(other).orElseThrow(), "other files are not swept in");
        assertEquals("first line only", git.log(root, 1).get(0).shortMessage());
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
