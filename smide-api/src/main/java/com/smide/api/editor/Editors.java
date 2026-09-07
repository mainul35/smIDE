package com.smide.api.editor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface Editors {

    /** The editor in the selected document tab of the selected workspace. */
    Optional<Editor> active();

    /** Shorthand for the active editor when it is a text editor. */
    default Optional<TextEditor> activeText() {
        return active().flatMap(Editor::asText);
    }

    List<Editor> open();

    /** Opens or selects the file, adopting its folder as a workspace if none contains it. */
    Editor open(Path file);

    /** Opens the file and puts the caret at a zero-based line and column. */
    Editor open(Path file, int line, int column);

    Optional<Editor> find(Path file);

    /** Closes the editor, prompting for unsaved changes; false if the user cancelled. */
    boolean close(Editor editor);

    void saveAll();

    /** Files opened recently, most recent first. */
    List<Path> recentFiles();

    void addOpenedListener(Consumer<Editor> listener);

    void addClosedListener(Consumer<Editor> listener);

    void addActiveListener(Consumer<Optional<Editor>> listener);
}
