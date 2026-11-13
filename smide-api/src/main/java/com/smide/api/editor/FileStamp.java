package com.smide.api.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What a file looked like when an editor last read or wrote it, so that a change made by
 * something else can be noticed before the editor writes over it.
 *
 * <p>An editor holds one of these and renews it on every read and every write. Anything that
 * is about to save then has a question it can answer - is the file still the one I read? - and
 * the answer decides between saving quietly and asking the user, which is the difference
 * between keeping their work and losing it.
 *
 * <p>Size as well as modified time, because modified time alone is not enough. Filesystems
 * keep it coarsely, a second on some, and two writes inside one tick are indistinguishable by
 * time while nearly always differing in length. Neither is proof - a file can be rewritten to
 * the same length within the same tick - but the pair is close enough to catch a person or a
 * program editing the file, which is what this is for. It is not a checksum and is not meant
 * to be: reading every byte of every open file to answer a question that is usually "no"
 * would cost more than the rare miss.
 *
 * <p>A file that cannot be read stamps as {@link #MISSING}, which is deliberately not equal to
 * any real stamp but is equal to itself: a file that has been deleted while open reads as
 * changed once, and then stops announcing it on every check.
 */
public record FileStamp(long size, long modified, boolean present) {

    /** What a file that is not there, or cannot be read, stamps as. */
    public static final FileStamp MISSING = new FileStamp(-1, -1, false);

    /** What a file has never been stamped as - an editor that has not read anything yet. */
    public static final FileStamp UNKNOWN = new FileStamp(-2, -2, false);

    /** How {@code file} looks now, or {@link #MISSING} if it cannot be read. */
    public static FileStamp of(Path file) {
        if (file == null) {
            return MISSING;
        }
        try {
            return new FileStamp(Files.size(file),
                    Files.getLastModifiedTime(file).toMillis(), true);
        } catch (IOException | RuntimeException e) {
            return MISSING;
        }
    }

    /**
     * Whether {@code file} differs from what this stamp recorded.
     *
     * <p>An editor that has never stamped anything says no: it has nothing to compare, and
     * answering yes would make every such editor look changed and ask about every save.
     */
    public boolean differsFrom(Path file) {
        if (equals(UNKNOWN)) {
            return false;
        }
        return !equals(of(file));
    }

    /** Whether this stamp came from a file that was really there. */
    public boolean exists() {
        return present;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FileStamp s
                && s.size == size && s.modified == modified && s.present == present;
    }

    @Override
    public int hashCode() {
        return Objects.hash(size, modified, present);
    }
}
