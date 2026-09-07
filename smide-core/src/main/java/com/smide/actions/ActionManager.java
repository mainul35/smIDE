package com.smide.actions;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.action.ActionContext;
import com.smide.api.action.Actions;
import com.smide.api.editor.Editor;
import com.smide.api.settings.Settings;
import com.smide.api.workspace.Workspace;
import com.smide.core.ExtensionRegistry;
import com.smide.ui.Icons;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCombination;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Every command in one registry, the menu bar built from it, and the shortcuts wired to
 * the scene. A user override in settings ({@code keymap.<id>}) replaces a default
 * shortcut; an empty override removes it.
 */
public final class ActionManager implements Actions {

    private static final List<String> MENU_ORDER = List.of(
            "File", "Edit", "View", "Navigate", "Code", "Refactor", "Build", "Run", "Tools", "VCS", "Window", "Help");

    private final Ide ide;
    private final ExtensionRegistry registry;
    private final Settings settings;
    private final MenuBar menuBar = new MenuBar();
    private final Map<String, MenuItem> menuItems = new HashMap<>();
    private final Map<KeyCombination, String> sceneAccelerators = new HashMap<>();
    private Scene scene;
    private Supplier<List<Path>> selectionProvider = List::of;
    private final Map<String, Supplier<List<MenuItem>>> dynamicMenus = new LinkedHashMap<>();
    private boolean rebuildScheduled;

    public ActionManager(Ide ide, ExtensionRegistry registry, Settings settings) {
        this.ide = ide;
        this.registry = registry;
        this.settings = settings;
        registry.onActionAdded(a -> scheduleRebuild());
        settings.addListener(key -> {
            if (key.startsWith("keymap.")) {
                scheduleRebuild();
            }
        });
    }

    public MenuBar menuBar() {
        return menuBar;
    }

    public void attach(Scene scene) {
        this.scene = scene;
        rebuild();
    }

    public void setSelectionProvider(Supplier<List<Path>> provider) {
        this.selectionProvider = provider;
    }

    /**
     * A submenu whose items are rebuilt every time it opens: Recent Workspaces, tool
     * windows. {@code path} is {@code Top/Sub}; the supplier runs on the JavaFX thread.
     */
    public void addDynamicMenu(String path, Supplier<List<MenuItem>> items) {
        dynamicMenus.put(path, items);
        scheduleRebuild();
    }

    private void scheduleRebuild() {
        if (rebuildScheduled) {
            return;
        }
        rebuildScheduled = true;
        Platform.runLater(() -> {
            rebuildScheduled = false;
            rebuild();
        });
    }

    // ------------------------------------------------------------------ menus

    private void rebuild() {
        menuBar.getMenus().clear();
        menuItems.clear();
        if (scene != null) {
            for (KeyCombination combo : sceneAccelerators.keySet()) {
                scene.getAccelerators().remove(combo);
            }
        }
        sceneAccelerators.clear();

        Map<String, Menu> topLevel = new LinkedHashMap<>();
        for (String name : MENU_ORDER) {
            topLevel.put(name, new Menu(name));
        }
        List<Action> sorted = new ArrayList<>(registry.actions());
        sorted.sort((a, b) -> Integer.compare(a.order(), b.order()));
        Map<Menu, Integer> lastGroup = new HashMap<>();

        for (Action action : sorted) {
            KeyCombination combo = effectiveCombination(action);
            if (action.menuPath() != null && !action.menuPath().isBlank()) {
                Menu menu = menuFor(topLevel, action.menuPath());
                int group = action.order() / 100;
                Integer previous = lastGroup.get(menu);
                if (previous != null && previous != group && !menu.getItems().isEmpty()) {
                    menu.getItems().add(new SeparatorMenuItem());
                }
                lastGroup.put(menu, group);
                MenuItem item = new MenuItem(action.text());
                FontIcon icon = Icons.of(action.iconLiteral());
                if (icon != null) {
                    item.setGraphic(icon);
                }
                if (combo != null) {
                    item.setAccelerator(combo);
                }
                item.setOnAction(e -> invoke(action.id()));
                menu.getItems().add(item);
                menuItems.put(action.id(), item);
            } else if (combo != null && scene != null) {
                scene.getAccelerators().put(combo, () -> invoke(action.id()));
                sceneAccelerators.put(combo, action.id());
            }
        }
        for (Map.Entry<String, Supplier<List<MenuItem>>> e : dynamicMenus.entrySet()) {
            Menu menu = menuFor(topLevel, e.getKey());
            Supplier<List<MenuItem>> supplier = e.getValue();
            menu.setOnShowing(ev -> menu.getItems().setAll(supplier.get()));
            menu.getItems().setAll(supplier.get());
        }
        for (Menu menu : topLevel.values()) {
            if (!menu.getItems().isEmpty()) {
                if (menu.getOnShowing() == null) {
                    menu.setOnShowing(e -> refreshEnabled(menu));
                }
                menuBar.getMenus().add(menu);
            }
        }
    }

    private Menu menuFor(Map<String, Menu> topLevel, String path) {
        String[] parts = path.split("/");
        Menu menu = topLevel.computeIfAbsent(parts[0].strip(), Menu::new);
        for (int i = 1; i < parts.length; i++) {
            String name = parts[i].strip();
            Menu sub = null;
            for (MenuItem item : menu.getItems()) {
                if (item instanceof Menu m && m.getText().equals(name)) {
                    sub = m;
                    break;
                }
            }
            if (sub == null) {
                sub = new Menu(name);
                menu.getItems().add(sub);
            }
            menu = sub;
        }
        return menu;
    }

    private void refreshEnabled(Menu menu) {
        ActionContext ctx = currentContext();
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu sub) {
                refreshEnabled(sub);
                continue;
            }
            for (Map.Entry<String, MenuItem> e : menuItems.entrySet()) {
                if (e.getValue() == item) {
                    byId(e.getKey()).ifPresent(a -> item.setDisable(!a.isEnabled(ctx)));
                }
            }
        }
    }

    // ------------------------------------------------------------- shortcuts

    private KeyCombination effectiveCombination(Action action) {
        String override = settings.get("keymap." + action.id(), null);
        String text = override != null ? override : action.shortcut();
        return parse(text);
    }

    public static KeyCombination parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return KeyCombination.keyCombination(text);
        } catch (RuntimeException e) {
            System.err.println("smIDE: bad shortcut '" + text + "'");
            return null;
        }
    }

    @Override
    public Optional<String> shortcutOf(String id) {
        return byId(id).map(this::effectiveCombination).map(KeyCombination::getDisplayText);
    }

    // ---------------------------------------------------------------- invoke

    @Override
    public List<Action> all() {
        return List.copyOf(registry.actions());
    }

    @Override
    public Optional<Action> byId(String id) {
        return registry.actions().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    @Override
    public void invoke(String id) {
        Optional<Action> action = byId(id);
        if (action.isEmpty()) {
            ide.statusBar().message("Unknown action: " + id);
            return;
        }
        ActionContext ctx = currentContext();
        if (!action.get().isEnabled(ctx)) {
            return;
        }
        try {
            action.get().run(ctx);
        } catch (RuntimeException e) {
            System.err.println("smIDE: action " + id + " failed: " + e);
            e.printStackTrace();
            ide.notifications().error("Action failed", action.get().text() + ": " + e.getMessage());
        }
    }

    @Override
    public ActionContext currentContext() {
        Optional<Workspace> workspace = ide.workspaces().active();
        Optional<Editor> editor = ide.editors().active();
        List<Path> selected = selectionProvider.get();
        if (selected.isEmpty() && editor.isPresent()) {
            selected = List.of(editor.get().path());
        }
        List<Path> files = selected;
        return new ActionContext() {
            @Override
            public Ide ide() {
                return ide;
            }

            @Override
            public Optional<Workspace> workspace() {
                return workspace;
            }

            @Override
            public Optional<Editor> editor() {
                return editor;
            }

            @Override
            public List<Path> selectedFiles() {
                return files;
            }
        };
    }
}
