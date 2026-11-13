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
