package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.ui.Notifications;
import com.smide.api.ui.StatusBar;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.editor.CodeEditor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Owns language-server sessions: one per (server, workspace), started when the first
 * file of that language opens in that workspace, stopped when the workspace closes.
 * Editors are bound to their session so the server sees every keystroke.
 */
public final class LspManager {

    private final Ide ide;
    private final Map<String, LspSession> sessions = new HashMap<>();
    private final Map<Editor, EditorLspBinding> bindings = new HashMap<>();
    private final Set<String> offered = new HashSet<>();
    private final Set<String> installing = new HashSet<>();
    private final List<Consumer<LspSession>> sessionListeners = new ArrayList<>();
    private final Set<String> failedNotified = new HashSet<>();

    public LspManager(Ide ide) {
        this.ide = ide;
        ide.editors().addOpenedListener(this::bind);
        ide.editors().addClosedListener(this::unbind);
        ide.workspaces().addClosedListener(this::workspaceClosed);
        ide.events().subscribe(Events.FileSaved.class, ev -> ide.editors().find(ev.path())
                .map(bindings::get).ifPresent(EditorLspBinding::saved));
        for (Editor e : ide.editors().open()) {
            bind(e);
        }
    }

    public void addSessionListener(Consumer<LspSession> listener) {
        sessionListeners.add(listener);
    }

    public Optional<EditorLspBinding> bindingOf(Editor editor) {
        return Optional.ofNullable(bindings.get(editor));
    }

    public Optional<LspSession> sessionFor(Editor editor) {
        return bindingOf(editor).map(EditorLspBinding::session);
    }

    private String key(LanguageServerLauncher launcher, Workspace workspace) {
        return launcher.serverId() + "@" + (launcher.perWorkspace() ? workspace.root() : "*");
    }

    private void bind(Editor editor) {
        if (!(editor instanceof CodeEditor code)) {
            return;
        }
        LanguageSupport language = code.language();
        Optional<LanguageServerLauncher> launcher = language.languageServer();
        if (launcher.isEmpty()) {
            return;
        }
        LanguageServerLauncher l = launcher.get();
        if (!l.isInstalled(ide)) {
            offerInstall(l, language);
            return;
        }
        Workspace workspace = editor.workspace();
        String key = key(l, workspace);
        LspSession session = sessions.get(key);
        if (session == null || session.state() == LspSession.State.FAILED
                || session.state() == LspSession.State.STOPPED) {
            if (session != null && session.state() == LspSession.State.FAILED
                    && !failedNotified.add(key)) {
                return; // Already failed once; do not retry on every open.
            }
            session = startSession(l, workspace, key);
        }
        EditorLspBinding binding = new EditorLspBinding(ide, this, code, session);
        bindings.put(editor, binding);
        binding.attach();
    }

    private LspSession startSession(LanguageServerLauncher launcher, Workspace workspace, String key) {
        LspSession session = new LspSession(ide, launcher, launcher.perWorkspace() ? workspace : null);
        sessions.put(key, session);
        StatusBar.Progress progress = ide.statusBar().progress("Starting " + launcher.displayName(), false);
        session.addStateListener(state -> {
            if (state != LspSession.State.STARTING) {
                progress.done();
            }
            if (state == LspSession.State.FAILED) {
                ide.notifications().error(launcher.displayName() + " failed",
                        session.failure() + "\nLog: " + ide.homeDir().resolve("logs")
                                .resolve(launcher.serverId() + ".log"));
            }
            if (state == LspSession.State.READY) {
                // Open every already-bound document for this session.
                for (EditorLspBinding b : List.copyOf(bindings.values())) {
                    if (b.session() == session) {
                        b.serverReady();
                    }
                }
            }
        });
        sessionListeners.forEach(l -> l.accept(session));
        session.start();
        return session;
    }

    private void unbind(Editor editor) {
        EditorLspBinding binding = bindings.remove(editor);
        if (binding != null) {
            binding.detach();
        }
    }

    private void workspaceClosed(Workspace workspace) {
        for (Map.Entry<String, LspSession> e : List.copyOf(sessions.entrySet())) {
            LspSession s = e.getValue();
            if (s.workspace() == workspace) {
                s.stop();
                sessions.remove(e.getKey());
                failedNotified.remove(e.getKey());
            }
        }
    }

    /** Restarts the session serving the active editor. */
    public void restart(Editor editor) {
        EditorLspBinding binding = bindings.get(editor);
        if (binding == null) {
            bind(editor);
            return;
        }
        LspSession old = binding.session();
        String key = key(old.launcher(), editor.workspace() == null ? old.workspace() : editor.workspace());
        old.stop();
        sessions.remove(key);
        failedNotified.remove(key);
        List<Editor> affected = new ArrayList<>();
        for (Map.Entry<Editor, EditorLspBinding> e : List.copyOf(bindings.entrySet())) {
            if (e.getValue().session() == old) {
                e.getValue().detach();
                bindings.remove(e.getKey());
                affected.add(e.getKey());
            }
        }
        affected.forEach(this::bind);
    }

    public List<LspSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public void stopAll() {
        sessions.values().forEach(LspSession::stop);
        sessions.clear();
    }

    // ------------------------------------------------------------------ install

    private void offerInstall(LanguageServerLauncher launcher, LanguageSupport language) {
        if (!offered.add(launcher.serverId())) {
            return;
        }
        Optional<LanguageServerLauncher.InstallRecipe> recipe = launcher.installRecipe();
        if (recipe.isEmpty()) {
            ide.notifications().info(launcher.displayName() + " not installed",
                    "Install it and put it on PATH to get completion and navigation for " + language.displayName() + ".");
            return;
        }
        ide.notifications().info(launcher.displayName() + " not installed",
                "Completion, navigation and errors for " + language.displayName() + " need it. "
                        + recipe.get().description(),
                new Notifications.NotificationAction("Install", () -> install(launcher, recipe.get())),
                new Notifications.NotificationAction("Not now", () -> {
                }));
    }

    public void install(LanguageServerLauncher launcher, LanguageServerLauncher.InstallRecipe recipe) {
        if (!installing.add(launcher.serverId())) {
            return;
        }
        StatusBar.Progress progress = ide.statusBar().progress("Installing " + launcher.displayName(), false);
        ide.window().runInBackground(() -> {
            try {
                recipe.run(ide, progress::update);
                ide.notifications().info(launcher.displayName() + " installed",
                        "Reopen a file to start it.");
                offered.remove(launcher.serverId());
                ide.window().runLater(() -> {
                    for (Editor e : ide.editors().open()) {
                        if (!bindings.containsKey(e)) {
                            bind(e);
                        }
                    }
                });
            } catch (Exception e) {
                ide.notifications().error("Install failed", launcher.displayName() + ": " + e.getMessage());
                offered.remove(launcher.serverId());
            } finally {
                installing.remove(launcher.serverId());
                progress.done();
            }
        });
    }
}
