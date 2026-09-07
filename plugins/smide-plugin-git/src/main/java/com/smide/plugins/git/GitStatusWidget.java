package com.smide.plugins.git;

import com.smide.api.Ide;
import com.smide.api.ui.StatusBarWidget;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The current branch on the status bar; clicking it offers the other branches. */
public final class GitStatusWidget implements StatusBarWidget {

    private final GitUi ui;
    private final Ide ide;
    private final HBox box = new HBox(4);
    private final Label label = new Label();

    public GitStatusWidget(GitUi ui) {
        this.ui = ui;
        this.ide = ui.ide();
        label.setGraphic(new FontIcon("fth-git-branch"));
        box.getChildren().add(label);
        box.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(box, new Tooltip("Current branch. Click to switch."));
        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                showBranchMenu(e.getScreenX(), e.getScreenY());
            }
        });
        ui.git().addListener(this::refresh);
        ide.workspaces().addActiveListener(w -> refresh());
        refresh();
    }

    private void refresh() {
        Optional<Path> root = ui.activeRoot();
        if (root.isEmpty()) {
            box.setVisible(false);
            box.setManaged(false);
            return;
        }
        box.setVisible(true);
        box.setManaged(true);
        ui.read(() -> ui.git().currentBranch(root.get()), label::setText);
    }

    private void showBranchMenu(double x, double y) {
        Optional<Path> root = ui.activeRoot();
        if (root.isEmpty()) {
            return;
        }
        ui.read(() -> ui.git().branches(root.get()), branches -> {
            ContextMenu menu = new ContextMenu();
            for (BranchInfo b : branches) {
                if (b.remote()) {
                    continue;
                }
                MenuItem item = new MenuItem((b.current() ? "* " : "   ") + b.name());
                item.setDisable(b.current());
                item.setOnAction(e -> ui.write("checked out " + b.name(),
                        () -> ui.git().checkout(root.get(), b.name())));
                menu.getItems().add(item);
            }
            if (!menu.getItems().isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem());
            }
            MenuItem create = new MenuItem("New branch...");
            create.setOnAction(e -> ide.window().prompt("New Branch", "Branch name", "")
                    .ifPresent(name -> ui.write("created " + name,
                            () -> ui.git().createBranch(root.get(), name, true))));
            MenuItem update = new MenuItem("Update project (pull)");
            update.setOnAction(e -> GitCommands.pull(ide, root.get()));
            MenuItem push = new MenuItem("Push");
            push.setOnAction(e -> GitCommands.push(ide, root.get()));
            menu.getItems().addAll(create, update, push);
            ide.theme().style(menu.getScene() == null ? box : (javafx.scene.Parent) box);
            menu.show(box, x, y);
        });
    }

    @Override
    public String id() {
        return "git.branch";
    }

    @Override
    public Node node() {
        return box;
    }

    @Override
    public int order() {
        return 40;
    }

    /** Branch names, for callers that need them without touching the service. */
    public List<String> branchNames() {
        return List.of();
    }
}
