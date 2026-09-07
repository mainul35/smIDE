package com.smide.api.plugin;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.editor.EditorProvider;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.lang.FileType;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.project.NewProjectTemplate;
import com.smide.api.project.ProjectImporter;
import com.smide.api.settings.SettingsPage;
import com.smide.api.ui.StatusBarWidget;
import com.smide.api.ui.ToolWindowFactory;

/**
 * Handed to {@link Plugin#start}. Registrations are scoped to the plugin and undone when
 * it stops.
 */
public interface PluginContext {

    PluginDescriptor descriptor();

    /** The IDE's services. */
    Ide ide();

    void registerFileType(FileType fileType);

    void registerLanguage(LanguageSupport language);

    void registerEditorProvider(EditorProvider provider);

    void registerToolWindow(ToolWindowFactory factory);

    void registerAction(Action action);

    void registerProjectImporter(ProjectImporter importer);

    void registerRunConfigurationType(RunConfigurationType type);

    void registerDebugger(com.smide.api.debug.Debugger debugger);

    void registerSettingsPage(SettingsPage page);

    void registerNewProjectTemplate(NewProjectTemplate template);

    void registerStatusBarWidget(StatusBarWidget widget);
}
