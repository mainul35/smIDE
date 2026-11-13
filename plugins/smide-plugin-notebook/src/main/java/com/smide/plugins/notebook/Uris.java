package com.smide.plugins.notebook;

import java.net.URI;
import java.util.Locale;

/**
 * Whether two {@code file:} URIs name the same document.
 *
 * <p>This exists because string equality does not work, and the way it fails is silent. A
 * language server is handed a URI and answers with one of its own making, and the two are
 * rarely the same text: Java writes {@code file:///C:/p/a.py}, while a server built on
 * vscode-uri - which pyright is - writes {@code file:///c%3A/p/a.py}, lower-casing the
 * drive letter and percent-encoding the colon. Comparing those as strings says they are
 * different documents, so every diagnostic the server publishes is dropped, and nothing
 * anywhere reports an error: the editor simply never underlines anything.
 *
 * <p>So the comparison is on the decoded path, and it ignores case on Windows, where the
 * file system does.
 */
final class Uris {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private Uris() {
    }

    /** Whether two URIs, however each was spelled, name one file. */
    static boolean same(String one, String other) {
        if (one == null || other == null) {
            return false;
        }
        if (one.equals(other)) {
            return true;
        }
        String first = path(one);
        String second = path(other);
        if (first == null || second == null) {
            return false;
        }
        return WINDOWS ? first.equalsIgnoreCase(second) : first.equals(second);
    }

    /**
     * The decoded path of a URI, with separators made one way and a leading slash before a
     * drive letter taken off, or null when it is not a URI at all.
     */
    private static String path(String uri) {
        try {
            String path = URI.create(uri).getPath();
            if (path == null) {
                return null;
            }
            path = path.replace('\\', '/');
            // "/C:/p/a.py" and "C:/p/a.py" are the same place.
            if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            return path;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
