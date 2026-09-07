package com.smide.plugins.git;

/**
 * A local or remote-tracking branch.
 *
 * @param name     the short name: {@code main} or {@code origin/main}
 * @param fullName the ref name: {@code refs/heads/main} or {@code refs/remotes/origin/main}
 * @param remote   true for a remote-tracking branch
 * @param current  true for the branch HEAD points at
 */
public record BranchInfo(String name, String fullName, boolean remote, boolean current) {

    @Override
    public String toString() {
        return name;
    }
}
