package com.smide.core;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.editor.EditorProvider;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.lang.FileType;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.plugin.PluginContext;
import com.smide.api.plugin.PluginDescriptor;
import com.smide.api.project.NewProjectTemplate;
import com.smide.api.project.ProjectImporter;
import com.smide.api.settings.SettingsPage;
import com.smide.api.ui.StatusBarWidget;
import com.smide.api.ui.ToolWindowFactory;

final class PluginContextImpl implements PluginContext {

    private final PluginDescriptor descriptor;
    private final Ide ide;
    private final ExtensionRegistry registry;

    PluginContextImpl(PluginDescriptor descriptor, Ide ide, ExtensionRegistry registry) {
        this.descriptor = descriptor;
        this.ide = ide;
        this.registry = registry;
    }

    @Override
    public PluginDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Ide ide() {
        return ide;
    }

    @Override
    public void registerFileType(FileType fileType) {
        registry.addFileType(fileType);
    }

    @Override
    public void registerLanguage(LanguageSupport language) {
        registry.addLanguage(language);
    }

    @Override
    public void registerEditorProvider(EditorProvider provider) {
        registry.addEditorProvider(provider);
    }

    @Override
    public void registerToolWindow(ToolWindowFactory factory) {
        registry.addToolWindow(factory);
    }

    @Override
    public void registerAction(Action action) {
        registry.addAction(action);
    }

    @Override
    public void registerProjectImporter(ProjectImporter importer) {
        registry.addImporter(importer);
    }

    @Override
    public void registerRunConfigurationType(RunConfigurationType type) {
        registry.addRunType(type);
    }

    @Override
    public void registerDebugger(com.smide.api.debug.Debugger debugger) {
        registry.addDebugger(debugger);
    }

    @Override
    public void registerSettingsPage(SettingsPage page) {
        registry.addSettingsPage(page);
    }

    @Override
    public void registerNewProjectTemplate(NewProjectTemplate template) {
        registry.addTemplate(template);
    }

    @Override
    public void registerStatusBarWidget(StatusBarWidget widget) {
        registry.addStatusWidget(widget);
    }

    @Override
    public void registerToolchain(com.smide.api.lang.Toolchain toolchain) {
        registry.addToolchain(toolchain);
    }
}
