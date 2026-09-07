package com.smide.workspace;

import com.smide.api.util.EventBus;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.api.workspace.Workspaces;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The open workspaces and the tab strip that shows them. Closing goes through a
 * {@code closeGuard} the editor manager installs, which asks about unsaved documents.
 */
public final class WorkspaceManager implements Workspaces {

    public static final int MAX_WORKSPACES = 10;

    private final TabPane workspaceTabs = new TabPane();
    private final List<WorkspaceImpl> workspaces = new ArrayList<>();
    private final WorkspaceHistory history;
    private final EventBus events;
    private final List<Consumer<Workspace>> openedListeners = new ArrayList<>();
    private final List<Consumer<Workspace>> closedListeners = new ArrayList<>();
    private final List<Consumer<Optional<Workspace>>> activeListeners = new ArrayList<>();
    private Predicate<WorkspaceImpl> closeGuard = w -> true;
    private Consumer<String> statusReporter = s -> {
    };

    public WorkspaceManager(WorkspaceHistory history, EventBus events) {
        this.history = history;
        this.events = events;
        workspaceTabs.getStyleClass().add("workspace-tabs");
        workspaceTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        workspaceTabs.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            Optional<Workspace> active = active();
            for (Consumer<Optional<Workspace>> l : List.copyOf(activeListeners)) {
                l.accept(active);
            }
        });
    }

    public TabPane tabPane() {
        return workspaceTabs;
    }

    public void setCloseGuard(Predicate<WorkspaceImpl> guard) {
        this.closeGuard = guard;
    }

    public void setStatusReporter(Consumer<String> reporter) {
        this.statusReporter = reporter;
    }

    @Override
    public List<Workspace> all() {
        return List.copyOf(workspaces);
    }

    public List<WorkspaceImpl> allImpl() {
        return workspaces;
    }

    @Override
    public Optional<Workspace> active() {
        return activeImpl().map(w -> w);
    }

    public Optional<WorkspaceImpl> activeImpl() {
        Tab selected = workspaceTabs.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return Optional.empty();
        }
        return workspaces.stream().filter(w -> w.tab() == selected).findFirst();
    }

    @Override
    public Workspace open(Path root) {
        return openImpl(root);
    }

    public WorkspaceImpl openImpl(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        for (WorkspaceImpl existing : workspaces) {
            if (existing.root().equals(normalized)) {
                workspaceTabs.getSelectionModel().select(existing.tab());
                return existing;
            }
        }
        if (workspaces.size() >= MAX_WORKSPACES) {
            statusReporter.accept("At most " + MAX_WORKSPACES + " workspaces can be open; close one first.");
            return activeImpl().orElseThrow();
        }
        WorkspaceImpl workspace = new WorkspaceImpl(normalized);
        workspaces.add(workspace);
        workspaceTabs.getTabs().add(workspace.tab());
        workspaceTabs.getSelectionModel().select(workspace.tab());
        history.record(normalized);
        workspace.tab().setOnCloseRequest(e -> {
            e.consume();
            close(workspace);
        });
        for (Consumer<Workspace> l : List.copyOf(openedListeners)) {
            l.accept(workspace);
        }
        events.publish(new Events.WorkspaceOpened(workspace));
        return workspace;
    }

    @Override
    public Optional<Workspace> containing(Path file) {
        return containingImpl(file).map(w -> w);
    }

    public Optional<WorkspaceImpl> containingImpl(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        // The deepest root wins when one workspace sits inside another.
        WorkspaceImpl best = null;
        for (WorkspaceImpl w : workspaces) {
            if (w.contains(file) && (best == null || w.root().getNameCount() > best.root().getNameCount())) {
                best = w;
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public boolean close(Workspace workspace) {
        if (!(workspace instanceof WorkspaceImpl impl) || !workspaces.contains(impl)) {
            return true;
        }
        if (!closeGuard.test(impl)) {
            return false;
        }
        workspaces.remove(impl);
        workspaceTabs.getTabs().remove(impl.tab());
        for (Consumer<Workspace> l : List.copyOf(closedListeners)) {
            l.accept(impl);
        }
        events.publish(new Events.WorkspaceClosed(impl));
        return true;
    }

    @Override
    public List<Path> recent() {
        return history.list();
    }

    public WorkspaceHistory history() {
        return history;
    }

    @Override
    public void addOpenedListener(Consumer<Workspace> listener) {
        openedListeners.add(listener);
    }

    @Override
    public void addClosedListener(Consumer<Workspace> listener) {
        closedListeners.add(listener);
    }

    @Override
    public void addActiveListener(Consumer<Optional<Workspace>> listener) {
        activeListeners.add(listener);
    }

    public void select(WorkspaceImpl workspace) {
        workspaceTabs.getSelectionModel().select(workspace.tab());
    }
}
