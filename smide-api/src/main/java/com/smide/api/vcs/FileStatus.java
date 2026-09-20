package com.smide.api.vcs;

/** What version control makes of a file, as far as the IDE needs to know. */
public enum FileStatus {

    /** Tracked, and the same as the last commit. */
    UNCHANGED,
    /** Tracked, and changed since the last commit. */
    MODIFIED,
    /** New: added to the index, or not tracked at all. */
    ADDED,
    /** Tracked, and gone from the working tree. */
    DELETED,
    /** A merge left it with conflicts in it. */
    CONFLICT,
    /** Deliberately not tracked - it matches an ignore rule. */
    IGNORED,
    /** Not in a repository, or nothing has been able to say. */
    UNKNOWN
}
