package com.smide.api.workspace;

import com.smide.api.project.ProjectModel;
import com.smide.api.settings.Settings;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A folder that is open in the IDE, with the documents opened from it.
 *
 * <p>Several can be open at once; each has its own tab of document tabs, its own project
 * model and its own settings under {@code <root>/.smide/}.
 */
public interface Workspace {

    Path root();

    /** The folder name, which is what the tab shows. */
    String name();

    /** The build system's view of the folder, once an importer has recognised it. */
    Optional<ProjectModel> project();

    /** Settings stored beside the code in {@code <root>/.smide/settings.json}. */
    Settings settings();

    /** {@code <root>/.smide}, created on first use. */
    Path configDir();

    /** True if {@code file} lives inside this workspace. */
    boolean contains(Path file);
}
