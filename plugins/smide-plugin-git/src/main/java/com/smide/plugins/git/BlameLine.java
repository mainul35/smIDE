package com.smide.plugins.git;

import java.time.Instant;

/**
 * Who last changed one line, and in which commit.
 *
 * @param line     zero-based line number in the file as it is now
 * @param commitId the full commit id, empty when the line is not committed yet
 * @param shortId  the abbreviated id
 * @param author   the author's name
 * @param date     when that commit was made, null for an uncommitted line
 * @param summary  the commit's subject line
 */
public record BlameLine(int line, String commitId, String shortId, String author, Instant date, String summary) {

    public boolean isCommitted() {
        return !commitId.isEmpty();
    }
}
