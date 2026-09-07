package com.smide.plugins.git;

import com.smide.api.Ide;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The bridge between the JavaFX thread and {@link GitService}, whose calls all block.
 *
 * <p>Every read goes to a background thread and comes back through {@code runLater};
 * every write does the same and then tells the service to notify its listeners, so the
 * Changes view, the Log and the status-bar branch refresh together.
 */
public final class GitUi {

    private final Ide ide;
    private final GitService git;

    public GitUi(Ide ide, GitService git) {
        this.ide = ide;
        this.git = git;
    }

    public Ide ide() {
        return ide;
    }

    public GitService git() {
        return git;
    }

    /** The active workspace's root, when it is inside a repository. */
    public Optional<Path> activeRoot() {
        return ide.workspaces().active().map(Workspace::root).filter(git::isRepository);
    }

    /** Runs {@code work} off the JavaFX thread and hands the result back on it. */
    public <T> void read(Supplier<T> work, Consumer<T> onResult) {
        ide.window().runInBackground(() -> {
            try {
                T value = work.get();
                ide.window().runLater(() -> onResult.accept(value));
            } catch (GitException e) {
                ide.window().runLater(() -> ide.statusBar().message("Git: " + e.getMessage()));
            } catch (RuntimeException e) {
                System.err.println("smIDE git: " + e);
                ide.window().runLater(() -> ide.statusBar().message("Git: " + e));
            }
        });
    }

    /**
     * Runs a change off the JavaFX thread, reports it, and refreshes every view.
     *
     * @param title what to say in the status bar when it worked
     */
    public void write(String title, Runnable work) {
        write(title, work, null);
    }

    public void write(String title, Runnable work, Runnable onDone) {
        ide.window().runInBackground(() -> {
            try {
                work.run();
                ide.window().runLater(() -> {
                    ide.statusBar().message("Git: " + title);
                    git.notifyChanged();
                    if (onDone != null) {
                        onDone.run();
                    }
                });
            } catch (GitException e) {
                ide.window().runLater(() -> ide.notifications().error("Git: " + title, e.getMessage()));
            } catch (RuntimeException e) {
                System.err.println("smIDE git: " + e);
                ide.window().runLater(() -> ide.notifications().error("Git: " + title, String.valueOf(e)));
            }
        });
    }
}
