package com.smide.api.action;

import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.editor.TextEditor;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What was in front of the user when an action ran: the active workspace and editor,
 * and the files selected in the explorer if the action came from there.
 */
public interface ActionContext {

    Ide ide();

    Optional<Workspace> workspace();

    Optional<Editor> editor();

    default Optional<TextEditor> textEditor() {
        return editor().flatMap(Editor::asText);
    }

    /** Files selected in the explorer, or the active editor's file. */
    List<Path> selectedFiles();

    default Optional<Path> selectedFile() {
        return selectedFiles().isEmpty() ? Optional.empty() : Optional.of(selectedFiles().get(0));
    }
}
