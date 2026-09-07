package com.smide.plugins.git;

/**
 * One changed file, as the working tree, the index or a commit sees it.
 *
 * @param path repository-relative path with forward slashes
 * @param kind what happened to it
 * @param area where the change lives (staged, unstaged, untracked, conflicting); for a
 *             commit's file list this is {@link Area#COMMITTED}
 */
public record FileChange(String path, Kind kind, Area area) {

    public enum Kind {
        ADDED('A'), MODIFIED('M'), DELETED('D'), RENAMED('R'), COPIED('C'), UNTRACKED('?'), CONFLICT('!');

        private final char letter;

        Kind(char letter) {
            this.letter = letter;
        }

        /** The one-letter status shown beside the file. */
        public char letter() {
            return letter;
        }
    }

    public enum Area {
        STAGED("Staged"), UNSTAGED("Unstaged"), UNTRACKED("Untracked"), CONFLICT("Conflicts"), COMMITTED("Committed");

        private final String title;

        Area(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** The file name without its directories. */
    public String fileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The directory part, or an empty string for a file at the root. */
    public String directory() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
