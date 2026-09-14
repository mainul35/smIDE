package com.smide.core;

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Everything plugins have registered, in one place, with listeners so the parts of the
 * window that show extensions (menus, stripes, the status bar) can pick up late ones.
 */
public final class ExtensionRegistry {

    private final List<FileType> fileTypes = new ArrayList<>();
    private final List<LanguageSupport> languages = new ArrayList<>();
    private final List<EditorProvider> editorProviders = new ArrayList<>();
    private final List<ToolWindowFactory> toolWindows = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private final List<ProjectImporter> importers = new ArrayList<>();
    private final List<RunConfigurationType> runTypes = new ArrayList<>();
    private final List<com.smide.api.debug.Debugger> debuggers = new ArrayList<>();
    private final List<SettingsPage> settingsPages = new ArrayList<>();
    private final List<NewProjectTemplate> templates = new ArrayList<>();
    private final List<StatusBarWidget> statusWidgets = new ArrayList<>();
    private final List<com.smide.api.lang.Toolchain> toolchains = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final List<Consumer<ToolWindowFactory>> toolWindowListeners = new ArrayList<>();
    private final List<Consumer<Action>> actionListeners = new ArrayList<>();
    private final List<Consumer<StatusBarWidget>> statusWidgetListeners = new ArrayList<>();

    public void addFileType(FileType t) {
        fileTypes.add(t);
    }

    public void addLanguage(LanguageSupport l) {
        languages.add(l);
    }

    public void addEditorProvider(EditorProvider p) {
        editorProviders.add(p);
        editorProviders.sort(Comparator.comparingInt(EditorProvider::priority).reversed());
    }

    public void addToolWindow(ToolWindowFactory f) {
        toolWindows.add(f);
        toolWindows.sort(Comparator.comparingInt(ToolWindowFactory::order));
        toolWindowListeners.forEach(l -> l.accept(f));
    }

    public void addAction(Action a) {
        actions.removeIf(existing -> existing.id().equals(a.id()));
        actions.add(a);
        actionListeners.forEach(l -> l.accept(a));
    }

    public void addImporter(ProjectImporter i) {
        importers.add(i);
        importers.sort(Comparator.comparingInt(ProjectImporter::priority).reversed());
    }

    public void addRunType(RunConfigurationType t) {
        runTypes.add(t);
    }

    public void addDebugger(com.smide.api.debug.Debugger d) {
        debuggers.add(d);
    }

    public List<com.smide.api.debug.Debugger> debuggers() {
        return debuggers;
    }

    public void addSettingsPage(SettingsPage p) {
        settingsPages.add(p);
    }

    public void addTemplate(NewProjectTemplate t) {
        templates.add(t);
    }

    public void addStatusWidget(StatusBarWidget w) {
        statusWidgets.add(w);
        statusWidgets.sort(Comparator.comparingInt(StatusBarWidget::order));
        statusWidgetListeners.forEach(l -> l.accept(w));
    }

    public void addToolchain(com.smide.api.lang.Toolchain t) {
        toolchains.add(t);
    }

    public List<com.smide.api.lang.Toolchain> toolchains() {
        return List.copyOf(toolchains);
    }

    public List<FileType> fileTypes() {
        return fileTypes;
    }

    public List<LanguageSupport> languages() {
        return languages;
    }

    public List<EditorProvider> editorProviders() {
        return editorProviders;
    }

    public List<ToolWindowFactory> toolWindows() {
        return toolWindows;
    }

    public List<Action> actions() {
        return actions;
    }

    public List<ProjectImporter> importers() {
        return importers;
    }

    public List<RunConfigurationType> runTypes() {
        return runTypes;
    }

    public List<SettingsPage> settingsPages() {
        return settingsPages;
    }

    public List<NewProjectTemplate> templates() {
        return templates;
    }

    public List<StatusBarWidget> statusWidgets() {
        return statusWidgets;
    }

    public void onToolWindowAdded(Consumer<ToolWindowFactory> l) {
        toolWindowListeners.add(l);
    }

    public void onActionAdded(Consumer<Action> l) {
        actionListeners.add(l);
    }

    public void onStatusWidgetAdded(Consumer<StatusBarWidget> l) {
        statusWidgetListeners.add(l);
    }
}
