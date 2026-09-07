package com.smide.actions;

import com.smide.api.action.Action;
import com.smide.api.action.ActionContext;
import com.smide.api.editor.Editor;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.RunConfiguration;
import com.smide.core.ExtensionRegistry;
import com.smide.core.IdeImpl;
import com.smide.editor.CodeEditor;
import com.smide.editor.EditorManager;
import com.smide.explorer.ExplorerToolWindow;
import com.smide.ui.ToolWindowManager;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The commands the core ships: file, edit, view, navigate, run and help. Everything a
 * plugin adds sits beside these in the same menus.
 */
public final class CoreActions {

    private CoreActions() {
    }

    public static void register(IdeImpl ide, ExtensionRegistry registry, EditorManager editors,
                                ExplorerToolWindow explorer, ToolWindowManager toolWindows) {
        Consumer<Action> add = registry::addAction;

        // ------------------------------------------------------------- File
        add.accept(Action.of("file.newFile", "New File...").menu("File").icon("fth-file-plus").order(10)
                .contextMenu("explorer")
                .enabledWhen(ctx -> ctx.workspace().isPresent())
                .perform(ctx -> newFile(ctx, editors, explorer)));
        add.accept(Action.of("file.newFolder", "New Folder...").menu("File").icon("fth-folder-plus").order(11)
                .contextMenu("explorer")
                .enabledWhen(ctx -> ctx.workspace().isPresent())
                .perform(ctx -> newFolder(ctx, explorer)));
        add.accept(Action.of("file.newProject", "New Project...").menu("File").icon("fth-package").order(12)
                .perform(ctx -> ide.showNewProject()));
        add.accept(Action.of("file.open", "Open File...").menu("File").shortcut("shortcut+O").icon("fth-file").order(20)
                .perform(ctx -> ide.window().chooseFile("Open File", startDir(ctx)).ifPresent(editors::open)));
        add.accept(Action.of("file.openFolder", "Open Folder...").menu("File").shortcut("shortcut+shift+O")
                .icon("fth-folder").order(21)
                .perform(ctx -> ide.window().chooseDirectory("Open Folder", startDir(ctx))
                        .ifPresent(p -> ide.workspaces().open(p))));
        add.accept(Action.of("file.recentWorkspaces", "Recent Workspaces...").menu("File").order(22)
                .perform(ctx -> ide.showRecentWorkspaces()));
        add.accept(Action.of("file.save", "Save").menu("File").shortcut("shortcut+S").icon("fth-save").order(30)
                .enabledWhen(ctx -> ctx.editor().isPresent())
                .perform(ctx -> ctx.editor().ifPresent(editors::save)));
        add.accept(Action.of("file.saveAll", "Save All").menu("File").shortcut("shortcut+shift+S").order(31)
                .perform(ctx -> editors.saveAll()));
        add.accept(Action.of("file.saveAs", "Save As...").menu("File").order(32)
                .enabledWhen(ctx -> ctx.editor().isPresent())
                .perform(ctx -> ctx.editor().ifPresent(e -> ide.window()
                        .chooseSaveFile("Save As", e.path().getParent(), e.path().getFileName().toString())
                        .ifPresent(target -> {
                            e.saveAs(target);
                            editors.close(e);
                            editors.open(target);
                        }))));
        add.accept(Action.of("file.reload", "Reload from Disk").menu("File").order(33)
                .enabledWhen(ctx -> ctx.editor().isPresent())
                .perform(ctx -> ctx.editor().ifPresent(Editor::reload)));
        add.accept(Action.of("file.closeTab", "Close Tab").menu("File").shortcut("shortcut+F4").order(40)
                .enabledWhen(ctx -> ctx.editor().isPresent())
                .perform(ctx -> ctx.editor().ifPresent(editors::close)));
        add.accept(Action.of("file.closeAllTabs", "Close All Tabs").menu("File").order(41)
                .perform(ctx -> ctx.workspace().ifPresent(w -> {
                    for (Editor e : editors.open()) {
                        if (e.workspace() == w && !editors.close(e)) {
                            return;
                        }
                    }
                })));
        add.accept(Action.of("file.closeWorkspace", "Close Workspace").menu("File").order(42)
                .enabledWhen(ctx -> ctx.workspace().isPresent())
                .perform(ctx -> ctx.workspace().ifPresent(w -> ide.workspaces().close(w))));
        add.accept(Action.of("file.settings", "Settings...").menu("File").shortcut("shortcut+alt+S")
                .icon("fth-settings").order(50)
                .perform(ctx -> ide.showSettings(null)));
        add.accept(Action.of("file.exit", "Exit").menu("File").order(60).perform(ctx -> ide.requestExit()));

        // Explorer-only file operations.
        add.accept(Action.of("file.openInEditor", "Open").contextMenu("explorer").order(0)
                .enabledWhen(ctx -> ctx.selectedFile().filter(Files::isRegularFile).isPresent())
                .perform(ctx -> ctx.selectedFile().ifPresent(editors::open)));
        add.accept(Action.of("file.rename", "Rename...").contextMenu("explorer").icon("fth-edit-2").order(200)
                .enabledWhen(ctx -> ctx.selectedFile().filter(p -> !isWorkspaceRoot(ide, p)).isPresent())
                .perform(ctx -> rename(ctx, ide, editors, explorer)));
        add.accept(Action.of("file.delete", "Delete").contextMenu("explorer").icon("fth-trash-2").order(201)
                .enabledWhen(ctx -> !ctx.selectedFiles().isEmpty()
                        && ctx.selectedFiles().stream().noneMatch(p -> isWorkspaceRoot(ide, p)))
                .perform(ctx -> delete(ctx, ide, editors, explorer)));
        add.accept(Action.of("file.copyPath", "Copy Path").contextMenu("explorer").icon("fth-copy").order(300)
                .enabledWhen(ctx -> ctx.selectedFile().isPresent())
                .perform(ctx -> ctx.selectedFile().ifPresent(p -> copy(p.toString(), ide))));
        add.accept(Action.of("file.copyRelativePath", "Copy Relative Path").contextMenu("explorer").order(301)
                .enabledWhen(ctx -> ctx.selectedFile().isPresent())
                .perform(ctx -> ctx.selectedFile().ifPresent(p -> copy(ide.workspaces().containing(p)
                        .map(w -> w.root().relativize(p).toString()).orElse(p.toString()), ide))));
        add.accept(Action.of("file.revealInFileManager", "Show in File Manager").contextMenu("explorer")
                .icon("fth-external-link").order(302)
                .enabledWhen(ctx -> ctx.selectedFile().isPresent())
                .perform(ctx -> ctx.selectedFile().ifPresent(p -> ide.window().revealInFileManager(p))));
        add.accept(Action.of("file.refreshExplorer", "Refresh").contextMenu("explorer").icon("fth-refresh-cw").order(400)
                .perform(ctx -> explorer.refreshAll()));

        // ------------------------------------------------------------- Edit
        add.accept(Action.of("edit.undo", "Undo").menu("Edit").shortcut("shortcut+Z").order(10)
                .enabledWhen(ctx -> ctx.textEditor().isPresent())
                .perform(ctx -> ctx.textEditor().ifPresent(t -> t.undo())));
        add.accept(Action.of("edit.redo", "Redo").menu("Edit").shortcut("shortcut+shift+Z").order(11)
                .enabledWhen(ctx -> ctx.textEditor().isPresent())
                .perform(ctx -> ctx.textEditor().ifPresent(t -> t.redo())));
        add.accept(Action.of("edit.cut", "Cut").menu("Edit").shortcut("shortcut+X").order(20)
                .perform(ctx -> code(ctx).ifPresent(c -> c.area().cut())));
        add.accept(Action.of("edit.copy", "Copy").menu("Edit").shortcut("shortcut+C").order(21)
                .perform(ctx -> code(ctx).ifPresent(c -> c.area().copy())));
        add.accept(Action.of("edit.paste", "Paste").menu("Edit").shortcut("shortcut+V").order(22)
                .perform(ctx -> code(ctx).ifPresent(c -> c.area().paste())));
        add.accept(Action.of("edit.selectAll", "Select All").menu("Edit").shortcut("shortcut+A").order(23)
                .perform(ctx -> code(ctx).ifPresent(c -> c.area().selectAll())));
        add.accept(Action.of("edit.find", "Find...").menu("Edit/Find").shortcut("shortcut+F").icon("fth-search").order(100)
                .enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> c.showFind(false))));
        add.accept(Action.of("edit.replace", "Replace...").menu("Edit/Find").shortcut("shortcut+R").order(101)
                .enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> c.showFind(true))));
        add.accept(Action.of("edit.findInPath", "Find in Files...").menu("Edit/Find").shortcut("shortcut+shift+F")
                .order(102).perform(ctx -> ide.showFindInPath()));
        add.accept(Action.of("edit.duplicateLine", "Duplicate Line or Selection").menu("Edit").shortcut("shortcut+D")
                .order(200).enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(CodeEditor::duplicateLineOrSelection)));
        add.accept(Action.of("edit.deleteLine", "Delete Line").menu("Edit").shortcut("shortcut+Y").order(201)
                .enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(CodeEditor::deleteLine)));
        add.accept(Action.of("edit.moveLineUp", "Move Line Up").menu("Edit").shortcut("alt+shift+UP").order(202)
                .enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> c.moveLines(true))));
        add.accept(Action.of("edit.moveLineDown", "Move Line Down").menu("Edit").shortcut("alt+shift+DOWN").order(203)
                .enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> c.moveLines(false))));
        add.accept(Action.of("edit.commentLine", "Comment with Line Comment").menu("Code").shortcut("shortcut+SLASH")
                .order(10).enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(CodeEditor::toggleLineComment)));
        add.accept(Action.of("edit.commentBlock", "Comment with Block Comment").menu("Code")
                .shortcut("shortcut+shift+SLASH").order(11).enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(CodeEditor::toggleBlockComment)));

        // --------------------------------------------------------- Navigate
        add.accept(Action.of("navigate.searchEverywhere", "Search Everywhere").menu("Navigate").icon("fth-search")
                .order(10).description("Double Shift").perform(ctx -> ide.showSearchEverywhere("")));
        add.accept(Action.of("navigate.gotoFile", "Go to File...").menu("Navigate").shortcut("shortcut+shift+N")
                .order(11).perform(ctx -> ide.showGotoFile()));
        add.accept(Action.of("navigate.findAction", "Find Action...").menu("Navigate").shortcut("shortcut+shift+A")
                .order(12).perform(ctx -> ide.showFindAction()));
        add.accept(Action.of("navigate.recentFiles", "Recent Files").menu("Navigate").shortcut("shortcut+E").order(13)
                .perform(ctx -> ide.showRecentFiles()));
        add.accept(Action.of("navigate.gotoLine", "Go to Line...").menu("Navigate").shortcut("shortcut+G").order(20)
                .enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> ide.window()
                        .prompt("Go to Line", "Line number", String.valueOf(c.caretLine() + 1))
                        .ifPresent(s -> {
                            try {
                                c.gotoLine(Integer.parseInt(s.strip()) - 1);
                            } catch (NumberFormatException ignored) {
                                ide.statusBar().message("Not a line number: " + s);
                            }
                        }))));
        add.accept(Action.of("navigate.back", "Back").menu("Navigate").shortcut("shortcut+alt+LEFT")
                .icon("fth-arrow-left").order(1)
                .enabledWhen(ctx -> editors.history().canGoBack())
                .perform(ctx -> {
                    if (!editors.navigateBack()) {
                        ide.statusBar().message("Nothing to go back to");
                    }
                }));
        add.accept(Action.of("navigate.forward", "Forward").menu("Navigate").shortcut("shortcut+alt+RIGHT")
                .icon("fth-arrow-right").order(2)
                .enabledWhen(ctx -> editors.history().canGoForward())
                .perform(ctx -> {
                    if (!editors.navigateForward()) {
                        ide.statusBar().message("Nothing to go forward to");
                    }
                }));
        add.accept(Action.of("navigate.revealInExplorer", "Select in Project").menu("Navigate").shortcut("alt+F1")
                .order(30).enabledWhen(ctx -> ctx.editor().isPresent())
                .perform(ctx -> ctx.editor().ifPresent(e -> {
                    toolWindows.show(ExplorerToolWindow.ID);
                    explorer.reveal(e.path(), true);
                })));

        // --------------------------------------------------------------- View
        add.accept(Action.of("view.toggleTheme", "Toggle Dark Theme").menu("View").icon("fth-moon").order(500)
                .perform(ctx -> ide.theme().setDark(!ide.theme().isDark())));
        add.accept(Action.of("view.hideAllToolWindows", "Hide All Tool Windows").menu("View")
                .shortcut("shortcut+shift+F12").order(400).perform(ctx -> toolWindows.hideAll()));

        // ---------------------------------------------------------- Breakpoints
        add.accept(Action.of("debug.toggleBreakpoint", "Toggle Breakpoint").menu("Run").shortcut("shortcut+F8")
                .icon("fth-circle").order(30)
                .enabledWhen(ctx -> code(ctx).isPresent())
                .perform(ctx -> code(ctx).ifPresent(c -> ide.breakpoints().toggle(c.path(), c.caretLine()))));
        add.accept(Action.of("debug.removeBreakpoints", "Remove All Breakpoints").menu("Run").order(31)
                .enabledWhen(ctx -> !ide.breakpoints().all().isEmpty())
                .perform(ctx -> ide.breakpoints().removeAll()));

        add.accept(Action.of("debug.resume", "Resume Program").menu("Run").shortcut("F9").order(40)
                .enabledWhen(ctx -> suspended(ide))
                .perform(ctx -> ide.debugWindow().session().resume()));
        add.accept(Action.of("debug.stepOver", "Step Over").menu("Run").shortcut("F8").order(41)
                .enabledWhen(ctx -> suspended(ide))
                .perform(ctx -> ide.debugWindow().session().stepOver()));
        add.accept(Action.of("debug.stepInto", "Step Into").menu("Run").shortcut("F7").order(42)
                .enabledWhen(ctx -> suspended(ide))
                .perform(ctx -> ide.debugWindow().session().stepInto()));
        add.accept(Action.of("debug.stepOut", "Step Out").menu("Run").shortcut("shift+F8").order(43)
                .enabledWhen(ctx -> suspended(ide))
                .perform(ctx -> ide.debugWindow().session().stepOut()));

        // ---------------------------------------------------------------- Run
        add.accept(Action.of("run.run", "Run").menu("Run").shortcut("shift+F10").icon("fth-play").toolbar("run")
                .order(10).enabledWhen(ctx -> ide.execution().selectedConfiguration().isPresent())
                .perform(ctx -> ide.execution().selectedConfiguration()
                        .ifPresent(c -> ide.execution().run(c, ExecutionMode.RUN))));
        add.accept(Action.of("run.debug", "Debug").menu("Run").shortcut("shift+F9").icon("mdi2b-bug").toolbar("run")
                .order(11).enabledWhen(ctx -> ide.execution().selectedConfiguration()
                        .filter(c -> c.type().supportsDebug()).isPresent())
                .perform(ctx -> ide.execution().selectedConfiguration()
                        .ifPresent(c -> ide.execution().run(c, ExecutionMode.DEBUG))));
        add.accept(Action.of("run.stop", "Stop").menu("Run").shortcut("shortcut+F2").icon("fth-square").toolbar("run")
                .order(12).enabledWhen(ctx -> !ide.execution().running().isEmpty())
                .perform(ctx -> ide.execution().running().forEach(h -> h.stop())));
        add.accept(Action.of("run.rerun", "Rerun").menu("Run").shortcut("shortcut+F5").order(13)
                .enabledWhen(ctx -> ide.executionService().lastRun().isPresent())
                .perform(ctx -> ide.executionService().lastRun()
                        .ifPresent(c -> ide.execution().run(c, ide.executionService().lastMode()))));
        add.accept(Action.of("run.editConfigurations", "Edit Configurations...").menu("Run").order(20)
                .enabledWhen(ctx -> ctx.workspace().isPresent())
                .perform(ctx -> ide.showRunConfigurations()));
        add.accept(Action.of("run.chooseAndRun", "Run...").menu("Run").shortcut("alt+shift+F10").order(21)
                .enabledWhen(ctx -> ctx.workspace().isPresent())
                .perform(ctx -> ide.showRunChooser()));

        // --------------------------------------------------------------- Help
        add.accept(Action.of("help.about", "About smIDE").menu("Help").order(100).perform(ctx -> ide.showAbout()));
        add.accept(Action.of("help.plugins", "Plugins...").menu("Help").order(10)
                .perform(ctx -> ide.showSettings("Plugins")));
    }

    /** True while a debug session is stopped somewhere and can be stepped. */
    private static boolean suspended(IdeImpl ide) {
        return ide.debugWindow() != null && ide.debugWindow().session() != null
                && ide.debugWindow().session().isSuspended();
    }

    private static Optional<CodeEditor> code(ActionContext ctx) {
        return ctx.editor().filter(e -> e instanceof CodeEditor).map(e -> (CodeEditor) e);
    }

    private static Path startDir(ActionContext ctx) {
        return ctx.workspace().map(w -> w.root()).orElse(Path.of(System.getProperty("user.home")));
    }

    private static boolean isWorkspaceRoot(IdeImpl ide, Path p) {
        return ide.workspaces().all().stream().anyMatch(w -> w.root().equals(p));
    }

    private static Path targetDirectory(ActionContext ctx) {
        Optional<Path> selected = ctx.selectedFile();
        if (selected.isPresent()) {
            return Files.isDirectory(selected.get()) ? selected.get() : selected.get().getParent();
        }
        return ctx.workspace().map(w -> w.root()).orElse(null);
    }

    private static void newFile(ActionContext ctx, EditorManager editors, ExplorerToolWindow explorer) {
        Path dir = targetDirectory(ctx);
        if (dir == null) {
            return;
        }
        ctx.ide().window().prompt("New File", "Name (folders are created as needed)", "").ifPresent(name -> {
            Path target = dir.resolve(name).normalize();
            try {
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) {
                    ctx.ide().statusBar().message("Already exists: " + target.getFileName());
                } else {
                    Files.createFile(target);
                }
                explorer.refresh(target.getParent());
                explorer.reveal(target, false);
                editors.open(target);
            } catch (IOException e) {
                ctx.ide().notifications().error("Cannot create file", e.getMessage());
            }
        });
    }

    private static void newFolder(ActionContext ctx, ExplorerToolWindow explorer) {
        Path dir = targetDirectory(ctx);
        if (dir == null) {
            return;
        }
        ctx.ide().window().prompt("New Folder", "Name", "").ifPresent(name -> {
            Path target = dir.resolve(name).normalize();
            try {
                Files.createDirectories(target);
                explorer.refresh(dir);
                explorer.reveal(target, true);
            } catch (IOException e) {
                ctx.ide().notifications().error("Cannot create folder", e.getMessage());
            }
        });
    }

    private static void rename(ActionContext ctx, IdeImpl ide, EditorManager editors, ExplorerToolWindow explorer) {
        Path source = ctx.selectedFile().orElse(null);
        if (source == null) {
            return;
        }
        ide.window().prompt("Rename", "New name", source.getFileName().toString()).ifPresent(name -> {
            Path target = source.resolveSibling(name);
            if (Files.exists(target)) {
                ide.statusBar().message("Already exists: " + name);
                return;
            }
            boolean wasOpen = editors.find(source).isPresent();
            if (wasOpen && !editors.find(source).map(editors::close).orElse(true)) {
                return;
            }
            try {
                Files.move(source, target);
                explorer.refresh(source.getParent());
                explorer.reveal(target, true);
                if (wasOpen && Files.isRegularFile(target)) {
                    editors.open(target);
                }
            } catch (IOException e) {
                ide.notifications().error("Cannot rename", e.getMessage());
            }
        });
    }

    private static void delete(ActionContext ctx, IdeImpl ide, EditorManager editors, ExplorerToolWindow explorer) {
        List<Path> targets = ctx.selectedFiles();
        if (targets.isEmpty()) {
            return;
        }
        String what = targets.size() == 1 ? targets.get(0).getFileName().toString() : targets.size() + " items";
        if (!ide.window().confirm("Delete", "Delete " + what + "? It goes to the recycle bin when the system has one.")) {
            return;
        }
        for (Path target : targets) {
            editors.find(target).ifPresent(editors::close);
            for (Editor e : editors.open()) {
                if (e.path().startsWith(target)) {
                    editors.close(e);
                }
            }
            try {
                if (!Trash.moveToTrash(target)) {
                    deleteRecursively(target);
                }
                explorer.refresh(target.getParent());
            } catch (IOException e) {
                ide.notifications().error("Cannot delete " + target.getFileName(), e.getMessage());
            }
        }
    }

    private static void deleteRecursively(Path target) throws IOException {
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(target);
    }

    private static void copy(String text, IdeImpl ide) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        ide.statusBar().message("Copied: " + text);
    }

    public static Optional<RunConfiguration> selected(IdeImpl ide) {
        return ide.execution().selectedConfiguration();
    }
}
