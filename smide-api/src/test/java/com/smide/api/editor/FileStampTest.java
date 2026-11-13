package com.smide.api.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStampTest {

    @TempDir
    Path dir;

    private Path file(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void aFileThatHasNotChangedDoesNotLookChanged() throws IOException {
        Path file = file("a.txt", "one");
        FileStamp stamp = FileStamp.of(file);
        assertFalse(stamp.differsFrom(file));
        assertTrue(stamp.exists());
    }

    @Test
    void aFileWrittenOverLooksChanged() throws IOException {
        Path file = file("a.txt", "one");
        FileStamp stamp = FileStamp.of(file);
        Files.writeString(file, "a different length entirely");
        assertTrue(stamp.differsFrom(file));
    }

    @Test
    void aFileOfTheSameLengthWrittenLaterLooksChanged() throws IOException {
        Path file = file("a.txt", "one");
        FileStamp stamp = FileStamp.of(file);
        // Same number of bytes: only the modified time can tell these apart.
        Files.writeString(file, "two");
        /* Moved on after the write, not before it. Writing sets the modified time itself,
           so setting it first and writing second throws the new time away - and the test
           then passes or fails on whether the two writes happened to land either side of
           a filesystem clock tick, which on Windows they usually do not. */
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 5_000));
        assertTrue(stamp.differsFrom(file));
    }

    @Test
    void aFileThatHasGoneLooksChanged() throws IOException {
        Path file = file("a.txt", "one");
        FileStamp stamp = FileStamp.of(file);
        Files.delete(file);
        assertTrue(stamp.differsFrom(file));
        assertEquals(FileStamp.MISSING, FileStamp.of(file));
        assertFalse(FileStamp.of(file).exists());
    }

    @Test
    void aFileThatIsStillGoneDoesNotKeepAnnouncingIt() throws IOException {
        Path gone = dir.resolve("never-existed.txt");
        FileStamp stamp = FileStamp.of(gone);
        // Stamped as missing, still missing: nothing has changed since.
        assertFalse(stamp.differsFrom(gone));
    }

    @Test
    void anEditorThatHasReadNothingNeverLooksStale() throws IOException {
        Path file = file("a.txt", "one");
        assertFalse(FileStamp.UNKNOWN.differsFrom(file));
        Files.writeString(file, "changed under it");
        // Still no: with nothing to compare, claiming a change would make every save ask.
        assertFalse(FileStamp.UNKNOWN.differsFrom(file));
    }

    @Test
    void unknownAndMissingAreNotTheSameThing() {
        assertNotEquals(FileStamp.UNKNOWN, FileStamp.MISSING);
        assertEquals(FileStamp.MISSING, FileStamp.MISSING);
    }

    @Test
    void nothingAtAllStampsAsMissingRatherThanThrowing() {
        assertEquals(FileStamp.MISSING, FileStamp.of(null));
        assertFalse(FileStamp.MISSING.differsFrom(null));
    }

    @Test
    void aDirectoryInPlaceOfAFileDoesNotThrow() throws IOException {
        Path sub = Files.createDirectory(dir.resolve("sub"));
        // Whatever it answers, it must not blow up in a save path.
        FileStamp.of(sub).differsFrom(sub);
    }
}
