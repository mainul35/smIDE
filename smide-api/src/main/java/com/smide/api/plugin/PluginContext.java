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

    /**
     * Something this language's projects need installed - a compiler, a runtime - so the IDE
     * can say what is missing, and where to get it, when such a project is opened without it.
     */
    void registerToolchain(com.smide.api.lang.Toolchain toolchain);

    /**
     * Where names in a kind of file lead, for Go to Declaration to ask before the language
     * server - a Maven artifact named in a pom, say, which the XML server cannot follow.
     */
    void registerDeclarationProvider(com.smide.api.editor.DeclarationProvider provider);

    /** Adds the libraries of the projects this plugin understands to the Project tree's External Libraries. */
    void registerLibraryProvider(com.smide.api.project.LibraryProvider provider);
}
