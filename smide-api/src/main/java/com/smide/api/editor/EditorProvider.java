package com.smide.api.editor;

import com.smide.api.workspace.Workspace;

import java.nio.file.Path;

/**
 * Supplies an editor for files it recognises. Providers are asked in order of
 * {@link #priority()} before the IDE falls back to its own text editor.
 */
public interface EditorProvider {

    String id();

    boolean accepts(Path file);

    Editor create(Workspace workspace, Path file);

    /** Higher wins. The default text editor sits at zero. */
    default int priority() {
        return 10;
    }
}
