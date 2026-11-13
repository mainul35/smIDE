package com.smide.plugins.notebook;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Which Python runs a notebook.
 *
 * <p>The nearest virtual environment at or above the notebook, and only then whatever
 * Python the machine has. Looking from the notebook rather than from the workspace root is
 * the whole point of this class, and it is not a refinement: a workspace in smIDE is
 * whatever folder was opened, and opening a single file adopts a folder that may be far
 * above the project the file belongs to - a notebook opened from a path can easily end up
 * in a workspace rooted at the home directory. Asking only the workspace root then finds
 * no virtual environment and falls back to the system Python, and the notebook runs in an
 * interpreter that has none of the project's packages while appearing to work.
 *
 * <p>The search stops at the workspace root where the root is above the notebook, and
 * otherwise after a few levels, so it cannot wander up to the drive and pick up somebody
 * else's environment.
 *
 * <p>The walk is a function of paths, so it is tested against real folders without an IDE.
 */
final class Interpreters {

    /** How far up to look when the workspace root is no help. */
    static final int LEVELS = 6;

    private Interpreters() {
    }

    /**
     * The interpreter for a notebook.
     *
     * @param notebook  the file being edited
     * @param root      the workspace root, which may be above the notebook, beside it, or
     *                  somewhere else entirely
     * @param venvAt    what a folder's virtual environment is, if it has one - in the IDE
     *                  this is the Python plugin's own {@code venvInterpreter}
     * @param fallback  the machine's Python, asked only when no environment was found
     */
    static Optional<Path> forNotebook(Path notebook, Path root,
                                      Function<Path, Optional<Path>> venvAt,
                                      java.util.function.Supplier<Optional<Path>> fallback) {
        for (Path folder : folders(notebook, root)) {
            Optional<Path> found = venvAt.apply(folder);
            if (found.isPresent()) {
                return found;
            }
        }
        return fallback.get();
    }

    /**
     * The folders to look in, nearest first.
     *
     * <p>The notebook's own folder, then each folder above it, and the workspace root last
     * where it was not already on the way up - a root that is not an ancestor is still
     * worth asking, because a project whose notebooks live in a sibling folder is a normal
     * thing to have.
     */
    static List<Path> folders(Path notebook, Path root) {
        List<Path> out = new ArrayList<>();
        Path normalRoot = root == null ? null : root.toAbsolutePath().normalize();
        Path at = notebook == null ? null : notebook.toAbsolutePath().normalize().getParent();
        for (int level = 0; at != null && level < LEVELS; level++) {
            out.add(at);
            if (at.equals(normalRoot)) {
                return List.copyOf(out);
            }
            at = at.getParent();
        }
        if (normalRoot != null && !out.contains(normalRoot)) {
            out.add(normalRoot);
        }
        return List.copyOf(out);
    }
}
