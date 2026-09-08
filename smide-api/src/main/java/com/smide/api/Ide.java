package com.smide.api;

import com.smide.api.action.Actions;
import com.smide.api.editor.Editors;
import com.smide.api.execution.Execution;
import com.smide.api.lang.Languages;
import com.smide.api.problems.Problems;
import com.smide.api.project.Projects;
import com.smide.api.settings.Settings;
import com.smide.api.ui.Notifications;
import com.smide.api.ui.StatusBar;
import com.smide.api.ui.Theme;
import com.smide.api.ui.ToolWindows;
import com.smide.api.ui.WindowService;
import com.smide.api.util.Downloads;
import com.smide.api.util.EventBus;
import com.smide.api.workspace.Workspaces;

import java.nio.file.Path;

/**
 * The IDE as one object: every service a plugin can reach.
 *
 * <p>Unless a method says otherwise, services must be called on the JavaFX application
 * thread; {@link WindowService#runLater} gets there from anywhere else.
 */
public interface Ide {

    String version();

    /** {@code ~/.smide}: settings, session, downloaded tools, logs. */
    Path homeDir();

    Workspaces workspaces();

    Editors editors();

    Languages languages();

    Projects projects();

    Actions actions();

    ToolWindows toolWindows();

    Execution execution();

    Problems problems();

    /** Breakpoints, shared by the gutter and whichever debugger runs. */
    com.smide.api.debug.Breakpoints breakpoints();

    Notifications notifications();

    StatusBar statusBar();

    Settings settings();

    /**
     * Opens the Settings dialog, at a page if one is named.
     *
     * <p>The path a page registered itself under - "Tools/Assistant" - or null for
     * wherever it opened last. A plugin that tells the user something is unconfigured
     * needs to be able to take them to where it is configured; without this the only
     * honest instruction is "look in Settings", and a page three levels into a tree is
     * not somewhere people look.
     */
    void showSettings(String page);

    Theme theme();

    EventBus events();

    Downloads downloads();

    WindowService window();
}
