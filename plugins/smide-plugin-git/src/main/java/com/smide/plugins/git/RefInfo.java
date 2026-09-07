package com.smide.plugins.git;

/**
 * Something a comparison can be made against: a branch, a remote branch or a tag.
 *
 * @param name     the short name, as a user would type it
 * @param fullName the full ref name
 * @param kind     what it is, which decides how it is grouped and labelled
 * @param current  true for the branch that is checked out
 */
public record RefInfo(String name, String fullName, Kind kind, boolean current) {

    public enum Kind {
        BRANCH("Branches"), REMOTE("Remote branches"), TAG("Tags");

        private final String title;

        Kind(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
