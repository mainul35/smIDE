package com.smide.api.util;

import com.smide.api.editor.Editor;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;

/** The events the core publishes on the {@link EventBus}. */
public final class Events {

    private Events() {
    }

    public record WorkspaceOpened(Workspace workspace) {
    }

    public record WorkspaceClosed(Workspace workspace) {
    }

    public record EditorOpened(Editor editor) {
    }

    public record EditorClosed(Editor editor) {
    }

    public record EditorActivated(Editor editor) {
    }

    public record FileSaved(Path path) {
    }

    /** The file system under a workspace changed; {@code path} is the directory whose listing changed. */
    public record FilesChanged(Workspace workspace, Path path) {
    }

    public record ThemeChanged(boolean dark) {
    }

    public record ProjectImported(Workspace workspace) {
    }
}
