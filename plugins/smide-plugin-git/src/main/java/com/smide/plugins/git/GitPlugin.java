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
