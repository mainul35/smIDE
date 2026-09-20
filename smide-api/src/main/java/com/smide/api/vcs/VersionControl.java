package com.smide.api.vcs;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What version control can tell the IDE about a file: whether it has changed, and what it looked
 * like when it was last committed.
 *
 * <p>Enough for the two things a reader wants at a glance - which lines they have touched since
 * the last commit, drawn beside those lines, and which files they have touched, coloured in the
 * tree. The comparison itself is not done here: the IDE compares the committed text with what is
 * in the editor, which may not have been saved, so a line goes green as it is typed rather than
 * when the file reaches the disk.
 *
 * <p>Every method here reads files and may be slow; none of them is called on the thread that
 * draws the window.
 */
public interface VersionControl {

    /** Which version control this is: {@code git}. */
    String id();

    /** Whether this file is inside a repository this can speak for. */
    boolean handles(Path file);

    /**
     * The file as the last commit has it, or empty when there is no such version - a new file, a
     * file outside a repository, a repository with no commits yet.
     */
    Optional<String> committedText(Path file);

    /** What has become of this file since the last commit. */
    FileStatus statusOf(Path file);

    /**
     * Told when what any of this would answer has changed: a commit, a checkout, something
     * staged, a file written outside the IDE.
     */
    void addChangeListener(Runnable listener);

    /** Whether one change in a file can be committed on its own, leaving the rest uncommitted. */
    default boolean canCommit(Path file) {
        return false;
    }

    /**
     * Commits this file as {@code content}, and nothing else.
     *
     * <p>What "commit this change" means: {@code content} is the last commit's version of the
     * file with one change applied, so the commit contains that change and no other - not the
     * file's other changes, not other files. The working tree is left exactly as it is, which is
     * the point: the rest of the work carries on uncommitted.
     *
     * @throws RuntimeException when the commit cannot be made, with a message worth showing
     */
    default void commitContent(Path file, String content, String message) {
        throw new UnsupportedOperationException("This version control cannot commit one change");
    }
}
