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
}
