package com.smide.editor;

import com.smide.api.editor.Editor;
import com.smide.api.editor.EditorProvider;
import com.smide.api.editor.Editors;
import com.smide.api.editor.TextEditor;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.settings.Settings;
import com.smide.api.ui.Notifications;
import com.smide.api.ui.Theme;
import com.smide.api.ui.WindowService;
import com.smide.api.util.EventBus;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.core.ExtensionRegistry;
import com.smide.lang.LanguageRegistry;
import com.smide.problems.ProblemsService;
import com.smide.workspace.EditorTab;
import com.smide.workspace.WorkspaceImpl;
import com.smide.workspace.WorkspaceManager;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Opens files into document tabs, tracks which editor is active, and guards closing
 * against unsaved changes.
 */
public final class EditorManager implements Editors {

    public static final int MAX_DOCUMENTS_PER_WORKSPACE = 30;
    private static final String RECENT_KEY = "editors.recent";
    private static final Set<String> PROJECT_MARKERS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "package.json",
            "Cargo.toml", "go.mod", "pyproject.toml", "setup.py", "requirements.txt", ".git", ".smide", "CMakeLists.txt",
            "Makefile", ".sln", "composer.json", "Gemfile", "mix.exs", "build.sbt");

    /** Where the IDE writes the read-only sources it pulls out of library jars. */
    private static final Path LIBRARY_SOURCES = Path.of(
            System.getProperty("smide.userHome", System.getProperty("user.home")), ".smide", "libraries");

    private final WorkspaceManager workspaces;
    private final ExtensionRegistry registry;
    private final LanguageRegistry languages;
    private final ProblemsService problems;
    private final WindowService window;
    private final Theme theme;
    private final Notifications notifications;
    private final EventBus events;
    private final Settings settings;
    private final com.smide.api.debug.Breakpoints breakpoints;
    private final List<Consumer<Editor>> openedListeners = new ArrayList<>();
    private final List<Consumer<Editor>> closedListeners = new ArrayList<>();
    private final List<Consumer<Optional<Editor>>> activeListeners = new ArrayList<>();
    private final NavigationHistory history = new NavigationHistory();
    private Editor lastActive;
    /** True while Back or Forward is driving, so the jump is not recorded again. */
    private boolean navigating;

    public EditorManager(WorkspaceManager workspaces, ExtensionRegistry registry, LanguageRegistry languages,
                         ProblemsService problems, WindowService window, Theme theme,
                         Notifications notifications, EventBus events, Settings settings,
                         com.smide.api.debug.Breakpoints breakpoints) {
        this.workspaces = workspaces;
        this.registry = registry;
        this.languages = languages;
        this.problems = problems;
        this.window = window;
        this.theme = theme;
        this.notifications = notifications;
        this.events = events;
        this.settings = settings;
        this.breakpoints = breakpoints;

        workspaces.setCloseGuard(this::closeAllIn);
        workspaces.addActiveListener(ws -> fireActive());
        workspaces.addOpenedListener(ws -> {
            WorkspaceImpl impl = (WorkspaceImpl) ws;
            impl.documentTabs().getSelectionModel().selectedItemProperty().addListener((o, a, b) -> fireActive());
        });
        problems.addListener(file -> find(file).flatMap(Editor::asText)
                .ifPresent(t -> t.setDiagnostics(problems.forFile(file))));
        // A breakpoint added anywhere - the gutter, an action, a session - repaints its file.
        breakpoints.addListener(file -> find(file)
                .filter(e -> e instanceof CodeEditor)
                .ifPresent(e -> ((CodeEditor) e).refreshGutter()));
        /* The font is read when an editor is built, so changing it in Settings reached
           the next file opened and none of the ones already on screen. Every open editor
           is told instead. */
        settings.addListener(key -> {
            if (key != null && key.startsWith("editor.")) {
                window.runLater(() -> {
                    for (Editor editor : open()) {
                        if (editor instanceof CodeEditor code) {
                            code.applyDisplaySettings();
                        }
                    }
                });
            }
        });
    }

    private void fireActive() {
        Optional<Editor> active = active();
        if (active.orElse(null) == lastActive) {
            return;
        }
        lastActive = active.orElse(null);
        for (Consumer<Optional<Editor>> l : List.copyOf(activeListeners)) {
            l.accept(active);
        }
        active.ifPresent(e -> events.publish(new Events.EditorActivated(e)));
    }

    @Override
    public Optional<Editor> active() {
        return workspaces.activeImpl().flatMap(WorkspaceImpl::selectedTab).map(EditorTab::editor);
    }

    @Override
    public List<Editor> open() {
        List<Editor> out = new ArrayList<>();
        for (WorkspaceImpl w : workspaces.allImpl()) {
            for (EditorTab t : w.editorTabs()) {
                out.add(t.editor());
            }
        }
        return out;
    }

    @Override
    public Editor open(Path file) {
        return open(file, -1, -1);
    }

    /** Where the caret is now, for the history to return to. */
    private Optional<NavigationHistory.Place> here() {
        return active().flatMap(Editor::asText)
                .map(t -> new NavigationHistory.Place(t.path(), t.caretLine(), t.caretColumn()));
    }

    public NavigationHistory history() {
        return history;
    }

    /** Steps back to the previous place; false when there is none. */
    public boolean navigateBack() {
        here().ifPresent(history::updateCurrent);
        return go(history.back());
    }

    public boolean navigateForward() {
        here().ifPresent(history::updateCurrent);
        return go(history.forward());
    }

    private boolean go(Optional<NavigationHistory.Place> place) {
        if (place.isEmpty()) {
            return false;
        }
        NavigationHistory.Place p = place.get();
        if (!Files.isRegularFile(p.file())) {
            history.forget(p.file());
            return false;
        }
        navigating = true;
        try {
            open(p.file(), p.line(), p.column());
        } finally {
            navigating = false;
        }
        return true;
    }

    @Override
    public Editor open(Path file, int line, int column) {
        Path target = file.toAbsolutePath().normalize();
        /* A jump is anything that asks for a position: a declaration, a search hit, a
           stack frame. Where the caret is leaving from is recorded first, so Back returns
           to the exact spot rather than to the top of the file. */
        if (line >= 0 && !navigating) {
            here().ifPresent(p -> {
                history.updateCurrent(p);
                history.record(p);
            });
        }
        if (!Files.isRegularFile(target)) {
            notifications.warn("File not found", target.toString());
            return null;
        }
        /* A file the IDE itself produced - the read-only source pulled out of a library
           jar - belongs to the project the reader came from, not to a project of its own.
           Left to the rule below it would open the IDE's own home directory as a
           workspace, which is not a project and is full of everything else the user owns. */
        WorkspaceImpl workspace = workspaces.containingImpl(target)
                .or(() -> target.startsWith(LIBRARY_SOURCES) ? workspaces.activeImpl() : Optional.empty())
                .orElseGet(() -> workspaces.openImpl(projectRootFor(target)));
        workspaces.select(workspace);

        Optional<EditorTab> existing = workspace.tabFor(target);
        EditorTab tab;
        if (existing.isPresent()) {
            tab = existing.get();
        } else {
            if (workspace.editorTabs().size() >= MAX_DOCUMENTS_PER_WORKSPACE) {
                notifications.warn("Too many open files",
                        "At most " + MAX_DOCUMENTS_PER_WORKSPACE + " files per workspace; close some first.");
                return null;
            }
            Editor editor = createEditor(workspace, target);
            if (editor == null) {
                return null;
            }
            tab = new EditorTab(editor, languages.iconFor(target));
            workspace.add(tab);
            tab.tab().setOnCloseRequest(e -> {
                e.consume();
                close(editor);
            });
            editor.asText().ifPresent(t -> t.setDiagnostics(problems.forFile(target)));
            rememberRecent(target);
            for (Consumer<Editor> l : List.copyOf(openedListeners)) {
                l.accept(editor);
            }
            events.publish(new Events.EditorOpened(editor));
        }
        workspace.documentTabs().getSelectionModel().select(tab.tab());
        Editor editor = tab.editor();
        if (line >= 0) {
            editor.asText().ifPresent(t -> t.moveCaret(line, Math.max(0, column)));
            if (!navigating) {
                history.record(new NavigationHistory.Place(target, line, Math.max(0, column)));
            }
        }
        Platform.runLater(editor::focus);
        return editor;
    }

    private Editor createEditor(WorkspaceImpl workspace, Path target) {
        for (EditorProvider provider : registry.editorProviders()) {
            try {
                if (provider.accepts(target)) {
                    Editor editor = provider.create(workspace, target);
                    if (editor != null) {
                        return editor;
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("smIDE: editor provider " + provider.id() + " failed: " + e);
                e.printStackTrace();
            }
        }
        if (!languages.isText(target)) {
            notifications.info("Binary file", target.getFileName() + " is not a text file; opening it outside the IDE.");
            window.revealInFileManager(target);
            return null;
        }
        try {
            if (Files.size(target) > 20L * 1024 * 1024) {
                notifications.warn("File too large", target.getFileName() + " is over 20 MB and was not opened.");
                return null;
            }
        } catch (java.io.IOException ignored) {
            // Open it anyway.
        }
        LanguageSupport language = languages.forFileOrPlain(target);
        CodeEditor editor = new CodeEditor(workspace, target, language, settings, breakpoints);
        if (target.startsWith(LIBRARY_SOURCES)) {
            editor.markExternalSource();
        }
        return editor;
    }

    /** The nearest ancestor that looks like a project root, or the file's own folder. */
    public static Path projectRootFor(Path file) {
        Path dir = file.getParent();
        Path candidate = dir;
        while (candidate != null) {
            for (String marker : PROJECT_MARKERS) {
                if (Files.exists(candidate.resolve(marker))) {
                    return candidate;
                }
            }
            candidate = candidate.getParent();
        }
        return dir == null ? file : dir;
    }

    @Override
    public Optional<Editor> find(Path file) {
        Path target = file.toAbsolutePath().normalize();
        for (WorkspaceImpl w : workspaces.allImpl()) {
            Optional<EditorTab> tab = w.tabFor(target);
            if (tab.isPresent()) {
                return Optional.of(tab.get().editor());
            }
        }
        return Optional.empty();
    }

    private Optional<WorkspaceImpl> owning(Editor editor) {
        for (WorkspaceImpl w : workspaces.allImpl()) {
            if (w.tabFor(editor).isPresent()) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean close(Editor editor) {
        Optional<WorkspaceImpl> owner = owning(editor);
        if (owner.isEmpty()) {
            return true;
        }
        if (!confirmDiscard(editor)) {
            return false;
        }
        removeEditor(owner.get(), editor);
        return true;
    }

    private void removeEditor(WorkspaceImpl workspace, Editor editor) {
        workspace.tabFor(editor).ifPresent(workspace::remove);
        try {
            editor.dispose();
        } catch (RuntimeException e) {
            System.err.println("smIDE: editor dispose failed: " + e);
        }
        for (Consumer<Editor> l : List.copyOf(closedListeners)) {
            l.accept(editor);
        }
        events.publish(new Events.EditorClosed(editor));
        fireActive();
    }

    /** The close guard for a workspace: every document must be saved or discarded. */
    private boolean closeAllIn(WorkspaceImpl workspace) {
        for (EditorTab tab : List.copyOf(workspace.editorTabs())) {
            if (!confirmDiscard(tab.editor())) {
                return false;
            }
        }
        for (EditorTab tab : List.copyOf(workspace.editorTabs())) {
            removeEditor(workspace, tab.editor());
        }
        return true;
    }

    /** Asks about unsaved changes. True means go ahead (saved or discarded). */
    public boolean confirmDiscard(Editor editor) {
        if (!editor.isModified()) {
            return true;
        }
        ButtonType save = new ButtonType("Save");
        ButtonType discard = new ButtonType("Discard");
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                editor.path().getFileName() + " has unsaved changes.", save, discard, ButtonType.CANCEL);
        alert.setTitle("Unsaved changes");
        alert.setHeaderText(null);
        alert.initOwner(window.stage());
        theme.style(alert.getDialogPane().getScene().getWindow());
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return false;
        }
        if (choice.get() == save) {
            try {
                editor.save();
                events.publish(new Events.FileSaved(editor.path()));
            } catch (RuntimeException e) {
                notifications.error("Save failed", e.getMessage());
                return false;
            }
        }
        return true;
    }

    public void save(Editor editor) {
        try {
            editor.save();
            events.publish(new Events.FileSaved(editor.path()));
        } catch (RuntimeException e) {
            notifications.error("Save failed", e.getMessage());
        }
    }

    @Override
    public void saveAll() {
        for (Editor editor : open()) {
            if (editor.isModified()) {
                save(editor);
            }
        }
    }

    /** Close every editor in every workspace, for shutdown; false if the user cancelled. */
    public boolean closeAll() {
        for (WorkspaceImpl w : List.copyOf(workspaces.allImpl())) {
            for (EditorTab tab : List.copyOf(w.editorTabs())) {
                if (!confirmDiscard(tab.editor())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Re-reads editors whose file changed on disk and that have no local edits. */
    public void reloadStale(Path directory) {
        for (Editor editor : open()) {
            if (editor.path().getParent() != null && editor.path().getParent().equals(directory)
                    && editor instanceof CodeEditor code && !code.isModified() && code.isStaleOnDisk()) {
                if (Files.exists(editor.path())) {
                    code.reload();
                }
            }
        }
    }

    // ----------------------------------------------------------- recent files

    private void rememberRecent(Path file) {
        List<String> recent = new ArrayList<>(settings.getList(RECENT_KEY));
        recent.remove(file.toString());
        recent.add(0, file.toString());
        while (recent.size() > 50) {
            recent.remove(recent.size() - 1);
        }
        settings.setList(RECENT_KEY, recent);
    }

    @Override
    public List<Path> recentFiles() {
        List<Path> out = new ArrayList<>();
        for (String s : settings.getList(RECENT_KEY)) {
            try {
                Path p = Path.of(s);
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            } catch (RuntimeException ignored) {
                // Skip malformed entries.
            }
        }
        return out;
    }

    @Override
    public void addOpenedListener(Consumer<Editor> listener) {
        openedListeners.add(listener);
    }

    @Override
    public void addClosedListener(Consumer<Editor> listener) {
        closedListeners.add(listener);
    }

    @Override
    public void addActiveListener(Consumer<Optional<Editor>> listener) {
        activeListeners.add(listener);
    }

    /** The editor for a tab, when a context menu needs it. */
    public Optional<Editor> editorOf(Tab tab) {
        return tab != null && tab.getUserData() instanceof EditorTab et ? Optional.of(et.editor()) : Optional.empty();
    }

    public Optional<TextEditor> activeText() {
        return active().flatMap(Editor::asText);
    }

    public Optional<Workspace> workspaceOf(Editor editor) {
        return owning(editor).map(w -> w);
    }
}
