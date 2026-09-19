package com.smide.api.project;

import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The libraries a project builds against, for the Project tool window's External Libraries -
 * where a dependency's jar and pom can be seen, browsed and found again from an open file,
 * as in IntelliJ.
 *
 * <p>Both methods are called off the UI thread and may read the disk.
 */
public interface LibraryProvider {

    /** Every library the workspace uses, its dependencies' dependencies included. */
    List<Library> libraries(Workspace workspace);

    /**
     * The library a file belongs to, when it is not among {@link #libraries} - a parent pom
     * followed from a pom, say - so that it can still be shown where it lives.
     */
    default Optional<Library> libraryOf(Path file) {
        return Optional.empty();
    }

    /**
     * @param name as the tree shows it: {@code Maven: org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0}
     * @param root the folder the library is kept in, or its jar
     */
    record Library(String name, Path root) {
    }
}
