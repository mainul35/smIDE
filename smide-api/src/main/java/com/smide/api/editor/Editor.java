package com.smide.api.editor;

import com.smide.api.workspace.Workspace;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.scene.Node;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Something that shows a file in a document tab.
 *
 * <p>The IDE's own text editor implements {@link TextEditor}; a plugin can provide a
 * different one for a file type through {@link EditorProvider} (the Markdown editor is one).
 */
public interface Editor {

    /** The node placed in the tab. */
    Node node();

    Path path();

    Workspace workspace();

    /** Whether there are unsaved changes; the tab label follows it. */
    ReadOnlyBooleanProperty modifiedProperty();

    default boolean isModified() {
        return modifiedProperty().get();
    }

    void save();

    /** Writes to a new path, and from then on {@link #path()} is that path. */
    void saveAs(Path target);

    /** The editor is being closed; stop watchers, release resources. */
    void dispose();

    /** Give keyboard focus to the content. */
    void focus();

    /**
     * Called the first time this editor's tab is brought to the front, and on every later
     * return to it.
     *
     * <p>For anything expensive that is only worth having once somebody is looking. A
     * session restored at startup opens its files into tabs behind the one that was left
     * in front, and a tab that is never clicked should cost no more than reading the file:
     * JavaFX keeps every tab's content in the scene whether or not it is the tab on show,
     * so this, and not the scene, is what says a reader has arrived.
     */
    default void shown() {
    }

    /** Re-read the file from disk because it changed outside the IDE. */
    void reload();

    /** This editor as a text editor, if it is one. */
    default Optional<TextEditor> asText() {
        return this instanceof TextEditor t ? Optional.of(t) : Optional.empty();
    }
}
