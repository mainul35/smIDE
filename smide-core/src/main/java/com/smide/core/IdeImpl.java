package com.smide.core;

import com.smide.SmIdeApp;
import com.smide.actions.ActionManager;
import com.smide.actions.CoreActions;
import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.action.Actions;
import com.smide.api.editor.Editor;
import com.smide.api.editor.Editors;
import com.smide.api.execution.Execution;
import com.smide.api.lang.Languages;
import com.smide.api.problems.Problems;
import com.smide.api.project.Projects;
import com.smide.api.settings.Settings;
import com.smide.api.ui.Notifications;
import com.smide.api.ui.StatusBar;
import com.smide.api.ui.Theme;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.ui.ToolWindows;
import com.smide.api.ui.WindowService;
import com.smide.api.util.Downloads;
import com.smide.api.util.EventBus;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.api.workspace.Workspaces;
import com.smide.editor.CodeEditor;
import com.smide.editor.EditorManager;
import com.smide.execution.ExecutionService;
import com.smide.execution.RunConfigurationsDialog;
import com.smide.execution.RunToolWindow;
import com.smide.explorer.ExplorerToolWindow;
import com.smide.lang.LanguageRegistry;
import com.smide.lsp.LspActions;
import com.smide.lsp.LspManager;
import com.smide.lsp.LspStatusWidget;
import com.smide.lsp.StructureToolWindow;
import com.smide.debug.BreakpointService;
import com.smide.debug.DebugToolWindow;
import com.smide.problems.ProblemsService;
import com.smide.problems.ProblemsToolWindow;
import com.smide.project.NewProjectDialog;
import com.smide.project.ProjectService;
import com.smide.search.FileIndex;
import com.smide.search.FindInPathToolWindow;
import com.smide.search.SearchPopups;
import com.smide.settings.JsonSettings;
import com.smide.settings.SettingsDialog;
import com.smide.theme.ThemeManager;
import com.smide.ui.MainWindow;
import com.smide.ui.NotificationCenter;
import com.smide.ui.StatusBarView;
import com.smide.ui.ToolWindowManager;
import com.smide.ui.WelcomeView;
import com.smide.workspace.EditorTab;
import com.smide.workspace.SessionStore;
import com.smide.workspace.WorkspaceHistory;
import com.smide.workspace.WorkspaceImpl;
import com.smide.workspace.WorkspaceManager;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The composition root: builds every service in dependency order, loads plugins, shows
 * the window, restores the session, and takes it all down again at exit.
 */
public final class IdeImpl implements Ide {

    private final Stage stage;
    private final Path homeDir;
    private final JsonSettings settings;
    private final EventBusImpl events;
    private final ThemeManager theme;
    private final WindowServiceImpl window;
    private final DownloadsImpl downloads;
    private final ExtensionRegistry registry = new ExtensionRegistry();
    private final LanguageRegistry languages;
    private final WorkspaceManager workspaces;
    private final ProblemsService problems;
    private final BreakpointService breakpoints;
    private final NotificationCenter notifications;
    private final StatusBarView statusBar;
    private final EditorManager editors;
    private final ProjectService projects;
    private final ExecutionService execution;
    private final ActionManager actions;
    private final PluginManager plugins;
    private final SessionStore sessionStore;
    private final FileIndex fileIndex = new FileIndex();
    private final com.smide.debug.dap.DebugAdaptersImpl debugAdapters = new com.smide.debug.dap.DebugAdaptersImpl(this);
    private ToolWindowManager toolWindows;
    private ExplorerToolWindow explorer;
    private FindInPathToolWindow findInPath;
    private DebugToolWindow debugWindow;
    private SearchPopups popups;
    private LspManager lsp;
    private MainWindow mainWindow;
    private final Label caretLabel = new Label();
    private final Label separatorLabel = new Label();
    private final Label languageLabel = new Label();
    /** Editors whose caret already updates the status bar. */
    private final Set<CodeEditor> caretWatched = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private FreezeReporter freezes;

    public IdeImpl(Stage stage, HostServices hostServices) {
        this.stage = stage;
        this.homeDir = Path.of(System.getProperty("smide.userHome", System.getProperty("user.home")), ".smide");
        this.settings = new JsonSettings(homeDir.resolve("settings.json"));
        this.events = new EventBusImpl();
        this.theme = new ThemeManager(settings, events);
        this.window = new WindowServiceImpl(stage, hostServices, theme);
        this.downloads = new DownloadsImpl(homeDir);
        this.languages = new LanguageRegistry(registry);
        this.workspaces = new WorkspaceManager(new WorkspaceHistory(homeDir.resolve("workspaces.txt")), events);
        this.problems = new ProblemsService();
        this.breakpoints = new BreakpointService(workspaces);
        this.notifications = new NotificationCenter();
        this.statusBar = new StatusBarView(registry, theme);
        this.editors = new EditorManager(workspaces, registry, languages, problems, window, theme, notifications,
                events, settings, breakpoints);
        this.projects = new ProjectService(this, registry);
        this.execution = new ExecutionService(this, registry);
        this.actions = new ActionManager(this, registry, settings);
        this.plugins = new PluginManager(this, registry);
        /* Every text editor gets the right-click menu the actions describe. Wired here
           rather than inside the editor, which knows nothing about actions. */
        this.editors.addOpenedListener(editor -> {
            if (editor instanceof com.smide.editor.CodeEditor code) {
                code.setContextMenu(actions.contextMenuFor("editor"));
                // While a debug session is stopped, pointing at a variable shows what is in it.
                com.smide.debug.DebugHover.install(this, code, () -> debugWindow);
            }
        });
        this.sessionStore = new SessionStore(homeDir.resolve("session.json"));
        workspaces.setStatusReporter(statusBar::message);
        // A project opened without the compiler or runtime its language needs is told so, once.
        workspaces.addOpenedListener(new ToolchainCheck(this, registry)::check);
    }

    // ------------------------------------------------------------------ start

    public void start(List<Path> openOnStart) {
        explorer = new ExplorerToolWindow(this, registry, languages, changed -> {
            for (Path dir : changed) {
                editors.reloadStale(dir);
                workspaces.containingImpl(dir).ifPresent(w -> {
                    fileIndex.invalidate(w.root());
                    events.publish(new Events.FilesChanged(w, dir));
                });
            }
        });
        registry.addToolWindow(explorer);
        registry.addToolWindow(new RunToolWindow(execution));
        registry.addToolWindow(new ProblemsToolWindow(this, problems));
        findInPath = new FindInPathToolWindow(this, languages, fileIndex);
        registry.addToolWindow(findInPath);
        debugWindow = new DebugToolWindow(this);
        registry.addToolWindow(debugWindow);
        execution.setDebugSessionSink(session -> {
            /* Shown through the manager, not by the window itself: a tool window that has
               never been created has no handle to show itself with, so a session would
               attach and stop with nothing on screen to say so. */
            debugWindow.setSession(session);
            toolWindows.show(DebugToolWindow.ID);
        });
        popups = new SearchPopups(this, languages, fileIndex);

        WelcomeView welcome = new WelcomeView(SmIdeApp.VERSION, List.of(
                new WelcomeView.ActionRow("New project...", null, () -> actions.invoke("file.newProject")),
                new WelcomeView.ActionRow("Open file...", "Ctrl+O", () -> actions.invoke("file.open")),
                new WelcomeView.ActionRow("Open folder...", "Ctrl+Shift+O", () -> actions.invoke("file.openFolder"))),
                root -> workspaces.open(root));

        toolWindows = new ToolWindowManager(registry, centerHolder);
        mainWindow = new MainWindow(this, stage, registry, workspaces, execution, toolWindows, statusBar,
                notifications, welcome);
        // The tool window manager wraps the centre the main window built.
        centerHolder.getChildren().add(mainWindow.centerNode());

        actions.setSelectionProvider(explorer::selectedPaths);
        CoreActions.register(this, registry, editors, explorer, toolWindows);
        lsp = new LspManager(this);
        registry.addToolWindow(new StructureToolWindow(this, lsp));
        registry.addStatusWidget(new LspStatusWidget(this, lsp));
        LspActions.register(this, lsp, registry);
        registerToolWindowActions();
        actions.addDynamicMenu("File/Recent Workspaces", this::recentWorkspaceItems);
        installStatusFacts();

        workspaces.addOpenedListener(w -> projects.reimport(w));
        // Detection reads the project model, so the chooser is only right once import ends.
        projects.addListener((w, model) -> execution.configurationsChanged());
        workspaces.addClosedListener(w -> {
            execution.workspaceClosed(w);
            fileIndex.invalidate(w.root());
        });
        editors.addActiveListener(e -> {
            mainWindow.refreshToolbarEnabled();
            updateStatusFacts(e);
        });
        workspaces.addActiveListener(w -> mainWindow.refreshToolbarEnabled());

        Set<String> disabled = new HashSet<>(settings.getList("plugins.disabled"));
        try {
            plugins.startAll(pluginsDir(), disabled);
        } catch (Throwable t) {
            // The window is worth more than any plugin; come up without them.
            System.err.println("smIDE: plugin loading failed: " + t);
            t.printStackTrace();
            notifications.error("Plugins failed to load", String.valueOf(t));
        }
        reportPluginFailures();

        SessionStore.Session session = sessionStore.load();
        SessionStore.WindowState win = session.window == null ? new SessionStore.WindowState() : session.window;
        mainWindow.show(win.x, win.y, win.width, win.height, win.maximized);

        boolean restore = settings.getBoolean("session.restore", true);
        if (restore && openOnStart.isEmpty()) {
            restoreSession(session);
        } else if (session.toolWindows == null || session.toolWindows.isEmpty()) {
            toolWindows.show(ExplorerToolWindow.ID);
        }
        for (Path p : openOnStart) {
            if (Files.isDirectory(p)) {
                workspaces.open(p);
            } else if (Files.isRegularFile(p)) {
                editors.open(p);
            }
        }
        if (!workspaces.all().isEmpty() && !toolWindows.isVisible(ExplorerToolWindow.ID)
                && (session.toolWindows == null || session.toolWindows.isEmpty())) {
            toolWindows.show(ExplorerToolWindow.ID);
        }
        /* When the window stops answering, what it was doing goes to logs/freezes. Not in the
           first seconds: every editor restored is laid out for the first time then, which
           takes a second or two on its own and would be reported at every start. */
        freezes = new FreezeReporter(homeDir.resolve("logs").resolve("freezes"), statusBar::message, 10_000);
        freezes.start();
    }

    private final javafx.scene.layout.StackPane centerHolder = new javafx.scene.layout.StackPane();

    private Path pluginsDir() {
        String prop = System.getProperty("smide.plugins");
        if (prop != null) {
            return Path.of(prop);
        }
        String home = System.getProperty("smide.home");
        if (home != null) {
            return Path.of(home, "plugins");
        }
        return null;
    }

    private void reportPluginFailures() {
        for (PluginManager.LoadedPlugin p : plugins.loaded()) {
            if (!p.isStarted() && !"disabled".equals(p.error())) {
                notifications.error("Plugin failed to start", p.descriptor().name() + ": " + p.error());
            }
        }
        if (plugins.loaded().stream().noneMatch(PluginManager.LoadedPlugin::isStarted)) {
            /* Nearly everything is a plugin: every language, the version control, the
               build tools, the terminal, the assistant. With none of them the window
               still opens and every file is plain text with no navigation - which looks
               like a dozen broken features rather than one missing class path. It
               happens when smIDE is started from a module that does not depend on the
               plugins, which is what running com.smide.Launcher out of smide-core does. */
            /* Short on purpose: the notification is clipped at 420 characters, and the
               half worth keeping is the half that says what to do about it. */
            notifications.warn("No plugins loaded",
                    "Highlighting, navigation, version control, build tools and the"
                            + " assistant are all plugins, and none were on the class path.\n\n"
                            + "Started from another smIDE? Fix it there, not here:\n"
                            + "Run > Edit Configurations > Classpath of module = smide-dist.\n"
                            + "A main class run from smide-core gets smide-core's class path.");
        }
    }

    private void registerToolWindowActions() {
        for (ToolWindowFactory f : registry.toolWindows()) {
            registerToolWindowAction(f);
        }
        registry.onToolWindowAdded(this::registerToolWindowAction);
    }

    private void registerToolWindowAction(ToolWindowFactory f) {
        Action action = Action.of("view.toolwindow." + f.id(), f.title()).menu("View/Tool Windows")
                .icon(f.iconLiteral()).order(f.order()).shortcut(f.shortcut())
                .perform(ctx -> toolWindows.toggle(f.id()));
        registry.addAction(action);
    }

    private List<MenuItem> recentWorkspaceItems() {
        List<MenuItem> items = new ArrayList<>();
        for (Path root : workspaces.recent()) {
            String parent = root.getParent() == null ? "" : "   " + root.getParent();
            MenuItem item = new MenuItem(root.getFileName() + parent);
            boolean open = workspaces.all().stream().anyMatch(w -> w.root().equals(root));
            item.setDisable(open);
            item.setOnAction(e -> workspaces.open(root));
            items.add(item);
        }
        if (items.isEmpty()) {
            MenuItem none = new MenuItem("No recent workspaces");
            none.setDisable(true);
            items.add(none);
        }
        return items;
    }

    // ------------------------------------------------------------ status bar

    private void installStatusFacts() {
        statusBar.editorFacts().getChildren().addAll(caretLabel, separatorLabel, languageLabel);
        caretLabel.setOnMouseClicked(e -> actions.invoke("navigate.gotoLine"));
        updateStatusFacts(Optional.empty());
    }

    private void updateStatusFacts(Optional<Editor> active) {
        Optional<CodeEditor> code = active.filter(e -> e instanceof CodeEditor).map(e -> (CodeEditor) e);
        if (code.isEmpty()) {
            caretLabel.setText("");
            separatorLabel.setText("");
            languageLabel.setText("");
            return;
        }
        CodeEditor c = code.get();
        Runnable update = () -> caretLabel.setText((c.caretLine() + 1) + ":" + (c.caretColumn() + 1));
        update.run();
        // Once per editor: it becomes active again and again, and each time used to add another.
        if (caretWatched.add(c)) {
            c.addCaretListener(() -> {
                if (editors.active().orElse(null) == c) {
                    update.run();
                }
            });
        }
        separatorLabel.setText(c.lineSeparatorName());
        languageLabel.setText(c.language().displayName());
    }

    // ---------------------------------------------------------------- session

    private void restoreSession(SessionStore.Session session) {
        for (SessionStore.WorkspaceState ws : session.workspaces) {
            try {
                Path root = Path.of(ws.root);
                if (!Files.isDirectory(root)) {
                    continue;
                }
                WorkspaceImpl workspace = workspaces.openImpl(root);
                for (SessionStore.FileState f : ws.files) {
                    Path file = Path.of(f.path);
                    if (Files.isRegularFile(file) && workspace.contains(file)) {
                        editors.open(file, f.line, f.column);
                    }
                }
                if (ws.activeFile != null) {
                    Path file = Path.of(ws.activeFile);
                    if (Files.isRegularFile(file)) {
                        editors.open(file);
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("smIDE: cannot restore " + ws.root + ": " + e);
            }
        }
        if (session.activeWorkspace != null) {
            for (WorkspaceImpl w : workspaces.allImpl()) {
                if (w.root().toString().equals(session.activeWorkspace)) {
                    workspaces.select(w);
                }
            }
        }
        if (session.toolWindows != null && !session.toolWindows.isEmpty()) {
            toolWindows.restore(session.toolWindows, session.dividers);
        } else {
            toolWindows.show(ExplorerToolWindow.ID);
        }
    }

    private SessionStore.Session captureSession() {
        SessionStore.Session session = new SessionStore.Session();
        for (WorkspaceImpl w : workspaces.allImpl()) {
            SessionStore.WorkspaceState ws = new SessionStore.WorkspaceState();
            ws.root = w.root().toString();
            for (EditorTab tab : w.editorTabs()) {
                SessionStore.FileState f = new SessionStore.FileState();
                f.path = tab.editor().path().toString();
                tab.editor().asText().ifPresent(t -> {
                    f.line = t.caretLine();
                    f.column = t.caretColumn();
                });
                ws.files.add(f);
            }
            w.selectedTab().ifPresent(t -> ws.activeFile = t.editor().path().toString());
            session.workspaces.add(ws);
        }
        workspaces.active().ifPresent(w -> session.activeWorkspace = w.root().toString());
        session.window.maximized = stage.isMaximized();
        if (!stage.isMaximized()) {
            session.window.x = stage.getX();
            session.window.y = stage.getY();
            session.window.width = stage.getWidth();
            session.window.height = stage.getHeight();
        }
        session.toolWindows = toolWindows.visibleByAnchor();
        session.dividers = toolWindows.dividerPositions();
        return session;
    }

    public void requestExit() {
        if (!editors.closeAll()) {
            return;
        }
        sessionStore.save(captureSession());
        shutdown();
        Platform.exit();
    }

    public void shutdown() {
        try {
            if (freezes != null) {
                // Stopping language servers takes a while, and is not the window freezing.
                freezes.stop();
            }
            if (lsp != null) {
                lsp.stopAll();
            }
            execution.stopAll();
            plugins.stopAll();
            window.shutdown();
        } catch (RuntimeException e) {
            System.err.println("smIDE: shutdown error: " + e);
        }
    }

    // ------------------------------------------------------------- dialogs

    public void showSettings(String page) {
        new SettingsDialog(this, registry).show(page);
    }

    public void showRunConfigurations() {
        workspaces.active().ifPresent(w -> new RunConfigurationsDialog(this, w).show());
    }

    public void showRunChooser() {
        popups.runChooser();
    }

    public void showNewProject() {
        new NewProjectDialog(this, registry).show();
    }

    public void showRecentWorkspaces() {
        popups.recentWorkspaces();
    }

    public void showSearchEverywhere(String initial) {
        popups.searchEverywhere(initial, null);
    }

    public void showGotoFile() {
        popups.gotoFile("");
    }

    public void showFindAction() {
        popups.findAction("");
    }

    public void showRecentFiles() {
        popups.recentFiles();
    }

    public void showFindInPath() {
        toolWindows.show(FindInPathToolWindow.ID);
        findInPath.focusWith(editors.activeText().map(t -> t.selectedText()).orElse(null));
    }

    public void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About smIDE");
        alert.setHeaderText("smIDE " + SmIdeApp.VERSION);
        alert.setContentText("A software development IDE in the JetBrains mould, with MDViewer's feel.\n\n"
                + "Java " + System.getProperty("java.version") + "   JavaFX " + System.getProperty("javafx.version")
                + "\nSettings: " + homeDir + "\nPlugins loaded: " + plugins.loaded().stream()
                .filter(PluginManager.LoadedPlugin::isStarted).count());
        alert.initOwner(stage);
        theme.style(alert.getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }

    // -------------------------------------------------------------- services

    public ActionManager actionManager() {
        return actions;
    }

    public LspManager lspManager() {
        return lsp;
    }

    public DebugToolWindow debugWindow() {
        return debugWindow;
    }

    public ExecutionService executionService() {
        return execution;
    }

    public JsonSettings jsonSettings() {
        return settings;
    }

    public PluginManager pluginManager() {
        return plugins;
    }

    public ExtensionRegistry registry() {
        return registry;
    }

    public FileIndex fileIndex() {
        return fileIndex;
    }

    @Override
    public String version() {
        return SmIdeApp.VERSION;
    }

    @Override
    public Path homeDir() {
        return homeDir;
    }

    @Override
    public Workspaces workspaces() {
        return workspaces;
    }

    @Override
    public Editors editors() {
        return editors;
    }

    @Override
    public Languages languages() {
        return languages;
    }

    @Override
    public Projects projects() {
        return projects;
    }

    @Override
    public Actions actions() {
        return actions;
    }

    @Override
    public ToolWindows toolWindows() {
        return toolWindows;
    }

    @Override
    public Execution execution() {
        return execution;
    }

    @Override
    public Problems problems() {
        return problems;
    }

    @Override
    public com.smide.api.debug.Breakpoints breakpoints() {
        return breakpoints;
    }

    @Override
    public Notifications notifications() {
        return notifications;
    }

    @Override
    public StatusBar statusBar() {
        return statusBar;
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public Theme theme() {
        return theme;
    }

    @Override
    public EventBus events() {
        return events;
    }

    @Override
    public Downloads downloads() {
        return downloads;
    }

    @Override
    public com.smide.api.debug.DebugAdapters debugAdapters() {
        return debugAdapters;
    }

    @Override
    public WindowService window() {
        return window;
    }

    /** The active workspace as the implementation, for core classes. */
    public Optional<Workspace> activeWorkspace() {
        return workspaces.active();
    }
}
