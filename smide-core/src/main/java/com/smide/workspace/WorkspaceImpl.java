package com.smide.workspace;

import com.smide.api.editor.Editor;
import com.smide.api.project.ProjectModel;
import com.smide.api.settings.Settings;
import com.smide.api.workspace.Workspace;
import com.smide.settings.JsonSettings;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A workspace and its tab. The workspace owns a {@link TabPane} of document tabs, which
 * is the content of the workspace's own tab - MDViewer's two-level model, which keeps
 * many open files legible by grouping them under the folder they came from.
 */
public final class WorkspaceImpl implements Workspace {

    private final Path root;
    private final Tab tab = new Tab();
    private final TabPane documentTabs = new TabPane();
    private final List<EditorTab> editors = new ArrayList<>();
    private final JsonSettings settings;
    private volatile ProjectModel project;

    public WorkspaceImpl(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.settings = new JsonSettings(configDir().resolve("settings.json"));
        tab.setContent(documentTabs);
        tab.setUserData(this);
        tab.setText(name());
        tab.setTooltip(new Tooltip(this.root.toString()));
        documentTabs.getStyleClass().add("document-tabs");
        documentTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public String name() {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    @Override
    public Optional<ProjectModel> project() {
        return Optional.ofNullable(project);
    }

    public void setProject(ProjectModel model) {
        this.project = model;
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public Path configDir() {
        return root.resolve(".smide");
    }

    @Override
    public boolean contains(Path file) {
        return file != null && file.toAbsolutePath().normalize().startsWith(root);
    }

    public Tab tab() {
        return tab;
    }

    public TabPane documentTabs() {
        return documentTabs;
    }

    public List<EditorTab> editorTabs() {
        return editors;
    }

    public Optional<EditorTab> tabFor(Path file) {
        Path target = file.toAbsolutePath().normalize();
        return editors.stream().filter(t -> t.editor().path().equals(target)).findFirst();
    }

    public Optional<EditorTab> tabFor(Editor editor) {
        return editors.stream().filter(t -> t.editor() == editor).findFirst();
    }

    public Optional<EditorTab> selectedTab() {
        Tab selected = documentTabs.getSelectionModel().getSelectedItem();
        return selected == null ? Optional.empty()
                : editors.stream().filter(t -> t.tab() == selected).findFirst();
    }

    public void add(EditorTab editorTab) {
        editors.add(editorTab);
        documentTabs.getTabs().add(editorTab.tab());
    }

    public void remove(EditorTab editorTab) {
        editors.remove(editorTab);
        documentTabs.getTabs().remove(editorTab.tab());
    }

    @Override
    public String toString() {
        return "Workspace(" + root + ")";
    }
}
