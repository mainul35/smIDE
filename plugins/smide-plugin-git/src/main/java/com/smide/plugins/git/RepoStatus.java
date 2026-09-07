package com.smide.plugins.git;

import java.util.List;

/**
 * A snapshot of a repository's working tree.
 *
 * @param branch      the current branch name, or a short id with "(detached)" when HEAD is detached
 * @param staged      changes in the index, relative to HEAD
 * @param unstaged    changes in the working tree, relative to the index
 * @param untracked   files Git does not know about
 * @param conflicting paths with unresolved merge conflicts
 */
public record RepoStatus(String branch,
                         List<FileChange> staged,
                         List<FileChange> unstaged,
                         List<FileChange> untracked,
                         List<FileChange> conflicting) {

    public boolean isClean() {
        return staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicting.isEmpty();
    }

    /** Whether tracked files differ from HEAD in the index or the working tree. */
    public boolean hasUncommittedChanges() {
        return !staged.isEmpty() || !unstaged.isEmpty() || !conflicting.isEmpty();
    }

    public int total() {
        return staged.size() + unstaged.size() + untracked.size() + conflicting.size();
    }
}
