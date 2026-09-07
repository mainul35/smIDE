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
}
