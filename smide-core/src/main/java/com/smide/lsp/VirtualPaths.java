package com.smide.lsp;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which file a server's answer is really about.
 *
 * <p>The CSS inside a page is given to the CSS server as a document of its own, under a
 * name of its own, and the server answers about that name. Nobody has a file called
 * {@code page.html.css} open, or on disk, so an answer under that name has to be turned
 * back into an answer about {@code page.html} before it reaches the Problems window.
 *
 * <p>Shared between every session, because the answer arrives on the server's own thread
 * inside the client for that server, and the document was opened by an editor that knows
 * nothing about that client. There is one IDE in the process and one entry per open
 * stretch of embedded code, so the map is small and lives as long as the documents do.
 */
final class VirtualPaths {

    private static final Map<Path, Path> REAL = new ConcurrentHashMap<>();

    private VirtualPaths() {
    }

    static void register(Path virtual, Path real) {
        REAL.put(virtual, real);
    }

    static void forget(Path virtual) {
        REAL.remove(virtual);
    }

    /** The file an answer about this name is about: itself, unless it is a stand-in. */
    static Path realOf(Path file) {
        Path real = REAL.get(file);
        return real == null ? file : real;
    }

    /** Whether anything is standing in for another file right now. */
    static boolean any() {
        return !REAL.isEmpty();
    }
}
