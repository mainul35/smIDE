package com.smide.api.project;

import com.smide.api.Ide;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Recognises a build system at a workspace root and reads its model. Runs on a
 * background thread; the model is published to {@link Projects} when done.
 */
public interface ProjectImporter {

    String id();

    /** Cheap: does the root have a {@code pom.xml}, a {@code build.gradle}? */
    boolean detects(Path root);

    ProjectModel importProject(Ide ide, Workspace workspace) throws IOException;

    /** Higher wins when several importers recognise the same folder. */
    default int priority() {
        return 0;
    }
}
