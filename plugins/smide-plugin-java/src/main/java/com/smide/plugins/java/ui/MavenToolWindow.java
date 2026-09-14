package com.smide.plugins.java.ui;

import com.smide.api.Ide;
import com.smide.api.execution.ProcessSpec;
import com.smide.api.project.ProjectModel;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.plugins.java.JavaProjectInfo;
import com.smide.plugins.java.JavaProjectRegistry;
import com.smide.plugins.java.JavaTools;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The build tool window: modules with their lifecycle phases, plugin goals and profiles.
 * Double-click runs a task in the Run tool window; toggles add {@code -DskipTests},
 * {@code -o} and the chosen profiles.
 */
public final class MavenToolWindow implements ToolWindowFactory {

    public static final String ID = "maven";

    private sealed interface Row permits ModuleRow, GroupRow, TaskRow, ProfileRow {
    }

    private record ModuleRow(String name, Path dir) implements Row {
    }

    private record GroupRow(String name) implements Row {
    }

    private record TaskRow(ProjectModel.BuildTask task) implements Row {
    }

    private record ProfileRow(String id) implements Row {
    }

    private final Ide ide;
    private final JavaProjectRegistry registry;
    private final TreeView<Row> tree = new TreeView<>();
    private final TreeItem<Row> root = new TreeItem<>();
    private final Label empty = new Label("Open a Maven or Gradle project to see its build.");
    private final StackPane pane = new StackPane();
    private final ToggleButton skipTests = new ToggleButton();
    private final ToggleButton offline = new ToggleButton();
    private final Set<String> activeProfiles = new LinkedHashSet<>();
    private Workspace current;
    private ToolWindowContext context;

    public MavenToolWindow(Ide ide, JavaProjectRegistry registry) {
        this.ide = ide;
        this.registry = registry;
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setCellFactory(v -> new RowCell());
        tree.setOnMouseClicked(e -> {
            TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
            if (item == null) {
                return;
            }
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && item.getValue() instanceof TaskRow t) {
                run(t.task());
            } else if (e.getButton() == MouseButton.PRIMARY && item.getValue() instanceof ProfileRow p) {
                if (!activeProfiles.remove(p.id())) {
                    activeProfiles.add(p.id());
                }
                tree.refresh();
            }
        });
        empty.getStyleClass().add("empty-hint");
        empty.setWrapText(true);
        pane.getChildren().addAll(empty, tree);

        skipTests.setGraphic(new FontIcon("fth-fast-forward"));
        skipTests.setTooltip(new Tooltip("Skip tests (-DskipTests)"));
        skipTests.getStyleClass().add("icon-button");
        offline.setGraphic(new FontIcon("fth-wifi-off"));
        offline.setTooltip(new Tooltip("Work offline (-o)"));
        offline.getStyleClass().add("icon-button");

        ide.workspaces().addActiveListener(w -> {
            current = w.orElse(null);
            refresh();
        });
        ide.events().subscribe(Events.ProjectImported.class, ev -> {
            if (ev.workspace() == current) {
                refresh();
            }
        });
        current = ide.workspaces().active().orElse(null);
    }

    private void refresh() {
        root.getChildren().clear();
        Optional<JavaProjectInfo> info = registry.get(current);
        if (current == null || info.isEmpty()) {
            empty.setVisible(true);
            tree.setVisible(false);
            if (context != null) {
                context.setTitle("Maven");
            }
            return;
        }
        ProjectModel model = info.get().model();
        boolean gradle = info.get().isGradle();
        if (context != null) {
            context.setTitle(gradle ? "Gradle" : "Maven");
        }
        Map<String, List<ProjectModel.BuildTask>> byGroup = new LinkedHashMap<>();
        for (ProjectModel.BuildTask task : model.tasks()) {
            byGroup.computeIfAbsent(task.group(), g -> new ArrayList<>()).add(task);
        }
        for (ProjectModel.ProjectModule module : model.modules()) {
            TreeItem<Row> moduleItem = new TreeItem<>(new ModuleRow(module.name(), module.root()));
            moduleItem.setExpanded(model.modules().size() == 1 || module.root().equals(model.root()));
            for (Map.Entry<String, List<ProjectModel.BuildTask>> e : byGroup.entrySet()) {
                String[] parts = e.getKey().split("/", 2);
                if (parts.length == 2 && parts[1].equals(module.name())) {
                    TreeItem<Row> group = new TreeItem<>(new GroupRow(parts[0]));
                    group.setExpanded("Lifecycle".equals(parts[0]) || "Tasks".equals(parts[0]));
                    for (ProjectModel.BuildTask t : e.getValue()) {
                        group.getChildren().add(new TreeItem<>(new TaskRow(t)));
                    }
                    moduleItem.getChildren().add(group);
                }
            }
            root.getChildren().add(moduleItem);
        }
        if (!info.get().profiles().isEmpty()) {
            TreeItem<Row> profiles = new TreeItem<>(new GroupRow("Profiles"));
            profiles.setExpanded(true);
            for (String id : info.get().profiles()) {
                profiles.getChildren().add(new TreeItem<>(new ProfileRow(id)));
            }
            root.getChildren().add(profiles);
        }
        empty.setVisible(false);
        tree.setVisible(true);
    }

    private void run(ProjectModel.BuildTask task) {
        if (current == null) {
            return;
        }
        Optional<JavaProjectInfo> info = registry.get(current);
        boolean gradle = info.map(JavaProjectInfo::isGradle).orElse(false);
        List<String> cmd = gradle ? JavaTools.gradle(ide, current.root()) : JavaTools.maven(ide, current.root());
        List<String> args = new ArrayList<>(task.command());
        args.remove(0);
        if (!gradle) {
            cmd.add("-B");
            if (skipTests.isSelected()) {
                cmd.add("-DskipTests");
            }
            if (offline.isSelected()) {
                cmd.add("-o");
            }
            if (!activeProfiles.isEmpty()) {
                cmd.add("-P" + String.join(",", activeProfiles));
            }
        } else if (offline.isSelected()) {
            cmd.add("--offline");
        } else if (skipTests.isSelected()) {
            cmd.add("-x");
            cmd.add("test");
        }
        cmd.addAll(args);
        Path cwd = task.workingDir() == null ? current.root() : task.workingDir();
        ide.execution().run(new ProcessSpec(task.name() + " [" + cwd.getFileName() + "]", cmd, cwd,
                JavaTools.environment(JavaTools.launchJdk(ide, current, registry).home())));
    }

    /** Runs an arbitrary goal line typed by the user. */
    public void runGoal(String line) {
        if (current == null || line == null || line.isBlank()) {
            return;
        }
        boolean gradle = registry.get(current).map(JavaProjectInfo::isGradle).orElse(false);
        List<String> cmd = gradle ? JavaTools.gradle(ide, current.root()) : JavaTools.maven(ide, current.root());
        if (!gradle) {
            cmd.add("-B");
        }
        cmd.addAll(com.smide.plugins.java.run.Forms.splitArgs(line));
        ide.execution().run(new ProcessSpec(line, cmd,
                com.smide.plugins.java.run.MavenLayout.buildRootFor(current.root()),
                JavaTools.environment(JavaTools.launchJdk(ide, current, registry).home())));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Maven";
    }

    @Override
    public String iconLiteral() {
        return "fth-package";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.RIGHT;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Node create(ToolWindowContext context) {
        this.context = context;
        Button reload = new Button();
        reload.setGraphic(new FontIcon("fth-refresh-cw"));
        reload.setTooltip(new Tooltip("Reload project"));
        reload.getStyleClass().add("icon-button");
        reload.setOnAction(e -> {
            if (current != null) {
                ide.projects().reimport(current);
            }
        });
        Button goal = new Button();
        goal.setGraphic(new FontIcon("fth-terminal"));
        goal.setTooltip(new Tooltip("Execute goal..."));
        goal.getStyleClass().add("icon-button");
        goal.setOnAction(e -> ide.window().prompt("Execute Maven Goal", "Command line", "clean install -DskipTests")
                .ifPresent(this::runGoal));
        Button deps = new Button();
        deps.setGraphic(new FontIcon("fth-git-merge"));
        deps.setTooltip(new Tooltip("Show dependency tree"));
        deps.getStyleClass().add("icon-button");
        deps.setOnAction(e -> runGoal(registry.get(current).map(JavaProjectInfo::isGradle).orElse(false)
                ? "dependencies" : "dependency:tree"));
        for (Button b : List.of(reload, goal, deps)) {
            b.setFocusTraversable(false);
        }
        HBox tools = new HBox(2, reload, goal, deps, skipTests, offline);
        tools.setAlignment(Pos.CENTER_LEFT);
        tools.setStyle("-fx-padding: 2 4 2 4;");
        BorderPane box = new BorderPane(pane);
        box.setTop(tools);
        refresh();
        return box;
    }

    private final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().remove("workspace-root");
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            switch (row) {
                case ModuleRow m -> {
                    setText(m.name());
                    setGraphic(new FontIcon("fth-box"));
                    getStyleClass().add("workspace-root");
                }
                case GroupRow g -> {
                    setText(g.name());
                    setGraphic(new FontIcon("fth-folder"));
                }
                case TaskRow t -> {
                    setText(t.task().name());
                    setGraphic(new FontIcon("fth-play-circle"));
                }
                case ProfileRow p -> {
                    setText((activeProfiles.contains(p.id()) ? "[x] " : "[ ] ") + p.id());
                    setGraphic(new FontIcon("fth-sliders"));
                }
            }
        }
    }
}
