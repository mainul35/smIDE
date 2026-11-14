package com.smide.api.project;

import com.smide.api.Ide;
import javafx.scene.Node;

import java.nio.file.Path;
import java.util.Map;

/** One entry in File → New Project: a form and a generator. */
public interface NewProjectTemplate {

    String id();

    String displayName();

    String description();

    String iconLiteral();

    /** Extra fields beyond name and location; values are collected into the map given to {@link #generate}. */
    Node form(Map<String, String> values);

    /** Creates the project under {@code location}. Background thread. */
    void generate(Ide ide, Path location, String name, Map<String, String> values) throws Exception;

    /**
     * The same, for a template that takes long enough to owe the reader an account of itself.
     *
     * <p>Most templates write a handful of files and are done before the dialog has closed,
     * and asking every one of them to report on that would be noise. A template that makes
     * an environment is a different thing: it runs other people's programs, over the
     * network, for minutes, and a progress indicator that says nothing for four minutes is
     * indistinguishable from one that has hung. Such a template overrides this and says what
     * it is doing; the rest inherit the version that ignores the handle.
     */
    default void generate(Ide ide, Path location, String name, Map<String, String> values,
                          com.smide.api.ui.StatusBar.Progress progress) throws Exception {
        generate(ide, location, name, values);
    }

    /**
     * Whether the reader may stop this template part way.
     *
     * <p>Only for a template that watches for it: a Cancel button on something that will not
     * stop is worse than no button, because it is pressed and nothing happens. A template
     * that says yes here must look at {@link com.smide.api.ui.StatusBar.Progress#isCancelled}
     * or register a handler with {@code onCancel}, and leave what it has already written
     * behind rather than tidying it away - a half-made project is the reader's to keep.
     */
    default boolean cancellable() {
        return false;
    }

    /**
     * The file to open once the new project is open, if there is an obvious one.
     *
     * <p>A project that has just been made has no history to restore, so it opens to nothing
     * at all and leaves the user looking at an empty editor area and a tree to go hunting in.
     * A template knows which file it made is the one to start in, and this is how it says so.
     * The IDE opens it after the project, and ignores a path that is not there.
     */
    default java.util.Optional<Path> openAfterCreating(Path location) {
        return java.util.Optional.empty();
    }
}
