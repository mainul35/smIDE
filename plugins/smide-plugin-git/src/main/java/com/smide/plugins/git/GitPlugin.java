package com.smide.plugins.git;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.action.ActionContext;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Git for smIDE: a Changes/Log tool window, diffs, commits and branches through JGit,
 * with pull and push handed to the system {@code git} so the user's credentials apply.
 */
public final class GitPlugin implements Plugin {

    private final GitService git = new GitService();
    private GitUi ui;
    private GitToolWindow toolWindow;

    @Override
    public void start(PluginContext context) {
        Ide ide = context.ide();
        ui = new GitUi(ide, git);
        toolWindow = new GitToolWindow(ui);
        context.registerToolWindow(toolWindow);
        context.registerStatusBarWidget(new GitStatusWidget(ui));

        // A repository handle caches refs; drop it when the workspace goes away.
        ide.workspaces().addClosedListener(w -> git.invalidate(w.root()));

        context.registerAction(Action.of("vcs.commit", "Commit...").menu("VCS").shortcut("shortcut+K")
                .icon("fth-git-commit").order(10)
                .enabledWhen(this::inRepository)
                .perform(ctx -> {
                    ide.toolWindows().show(GitToolWindow.ID);
                    toolWindow.focusCommitMessage();
                }));
        context.registerAction(Action.of("vcs.update", "Update Project (pull)").menu("VCS").shortcut("shortcut+T")
                .icon("fth-download").order(11)
                .enabledWhen(this::inRepository)
                .perform(ctx -> root(ctx).ifPresent(r -> GitCommands.pull(ide, r))));
        context.registerAction(Action.of("vcs.push", "Push").menu("VCS").shortcut("shortcut+shift+K")
                .icon("fth-upload").order(12)
                .enabledWhen(this::inRepository)
                .perform(ctx -> root(ctx).ifPresent(r -> GitCommands.push(ide, r))));
        context.registerAction(Action.of("vcs.fetch", "Fetch").menu("VCS").order(13)
                .enabledWhen(this::inRepository)
                .perform(ctx -> root(ctx).ifPresent(r -> GitCommands.fetch(ide, r))));
        context.registerAction(Action.of("vcs.log", "Show Git Log").menu("VCS").icon("fth-git-branch").order(20)
                .perform(ctx -> ide.toolWindows().show(GitToolWindow.ID)));
        context.registerAction(Action.of("vcs.newBranch", "New Branch...").menu("VCS").order(30)
                .enabledWhen(this::inRepository)
                .perform(ctx -> root(ctx).ifPresent(r -> ide.window().prompt("New Branch", "Branch name", "")
                        .ifPresent(name -> ui.write("created " + name, () -> git.createBranch(r, name, true))))));
        context.registerAction(Action.of("vcs.checkout", "Checkout Branch...").menu("VCS").order(31)
                .enabledWhen(this::inRepository)
                .perform(ctx -> root(ctx).ifPresent(r -> ui.read(() -> git.branches(r), branches -> {
                    List<String> names = branches.stream().filter(b -> !b.remote() && !b.current())
                            .map(BranchInfo::name).toList();
                    if (names.isEmpty()) {
                        ide.statusBar().message("No other local branches");
                        return;
                    }
                    ide.window().prompt("Checkout Branch", "One of: " + String.join(", ", names), names.get(0))
                            .ifPresent(name -> ui.write("checked out " + name, () -> git.checkout(r, name)));
                }))));
        context.registerAction(Action.of("vcs.init", "Initialize Repository").menu("VCS").order(40)
                .enabledWhen(ctx -> ctx.workspace().isPresent() && !inRepository(ctx))
                .perform(ctx -> ctx.workspace().map(Workspace::root)
                        .ifPresent(r -> ui.write("repository initialised", () -> git.init(r)))));
        context.registerAction(Action.of("vcs.diff", "Show Diff").menu("VCS").contextMenu("explorer")
                .icon("fth-file-text").order(50)
                .enabledWhen(ctx -> inRepository(ctx) && ctx.selectedFile().isPresent())
                .perform(ctx -> {
                    ide.toolWindows().show(GitToolWindow.ID);
                    toolWindow.refresh();
                }));

        // ------------------------------------------------- history and comparison

        context.registerAction(Action.of("vcs.history", "Show File History").menu("VCS")
                .contextMenu("explorer").contextMenu("editor").icon("fth-clock").order(51)
                .enabledWhen(ctx -> inRepository(ctx) && file(ctx).isPresent())
                .perform(ctx -> file(ctx).ifPresent(f -> {
                    /* Opened through the manager first: a tool window that has never been
                       created has no handle to show itself with, so the history would be
                       loaded into a panel that is not on screen. */
                    ide.toolWindows().show(GitToolWindow.ID);
                    toolWindow.showHistory(f);
                })));
        context.registerAction(Action.of("vcs.compare", "Compare with Branch or Revision...").menu("VCS")
                .contextMenu("explorer").contextMenu("editor").order(52)
                .enabledWhen(ctx -> inRepository(ctx) && file(ctx).isPresent())
                .perform(ctx -> file(ctx).ifPresent(f -> GitCompare.open(ide, ui, f, null))));
        context.registerAction(Action.of("vcs.compareSelection", "Compare Selection with Branch or Revision...")
                .menu("VCS").contextMenu("editor").order(53)
                .enabledWhen(ctx -> inRepository(ctx) && selectedLines(ctx) != null)
                .perform(ctx -> file(ctx).ifPresent(f -> GitCompare.open(ide, ui, f, selectedLines(ctx)))));
        context.registerAction(Action.of("vcs.annotate", "Annotate with Git Blame").menu("VCS")
                .contextMenu("editor").shortcut("shortcut+alt+A").order(54)
                .enabledWhen(ctx -> inRepository(ctx) && ctx.textEditor().isPresent() && file(ctx).isPresent())
                .perform(ctx -> ctx.textEditor().ifPresent(editor -> file(ctx).ifPresent(f ->
                        GitBlame.toggle(ide, ui, editor, f, toolWindow::showCommit)))));
    }

    /** The file an action is about: the editor's, or the one picked in the explorer. */
    private static Optional<Path> file(ActionContext ctx) {
        return ctx.editor().map(com.smide.api.editor.Editor::path).or(ctx::selectedFile);
    }

    /**
     * The lines a selection covers, {@code [first, last]} zero-based, or null when
     * nothing is selected. A selection that ends at the very start of a line does not
     * include that line, which is what the user sees on screen.
     */
    private static int[] selectedLines(ActionContext ctx) {
        return ctx.textEditor().map(editor -> {
            int start = editor.selectionStart();
            int end = editor.selectionEnd();
            if (end <= start) {
                return null;
            }
            int first = editor.lineOf(start);
            int last = editor.lineOf(end);
            if (last > first && editor.columnOf(end) == 0) {
                last--;
            }
            return new int[]{first, last};
        }).orElse(null);
    }

    private boolean inRepository(ActionContext ctx) {
        return ctx.workspace().map(Workspace::root).filter(git::isRepository).isPresent();
    }

    private Optional<Path> root(ActionContext ctx) {
        return ctx.workspace().map(Workspace::root).filter(git::isRepository);
    }

    @Override
    public void stop() {
        git.close();
    }
}
