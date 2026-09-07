package com.smide.api.action;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A command the user can invoke: from a menu, the toolbar, a shortcut, a context menu
 * or Find Action. One object serves all of those, so a shortcut change in Settings
 * changes every place the action appears.
 *
 * <p>Built with the fluent API:
 * <pre>{@code
 * Action.of("java.run", "Run")
 *       .icon("fth-play").shortcut("shift+F10").menu("Run")
 *       .enabledWhen(ctx -> ctx.workspace().isPresent())
 *       .perform(ctx -> ...);
 * }</pre>
 */
public final class Action {

    private final String id;
    private final String text;
    private String description;
    private String iconLiteral;
    private String shortcut;
    private String menuPath;
    private String toolbarGroup;
    private final java.util.Set<String> contextMenus = new java.util.LinkedHashSet<>();
    private int order = 100;
    private Predicate<ActionContext> enabled = ctx -> true;
    private Consumer<ActionContext> handler = ctx -> {
    };

    private Action(String id, String text) {
        this.id = id;
        this.text = text;
    }

    public static Action of(String id, String text) {
        return new Action(id, text);
    }

    public Action description(String description) {
        this.description = description;
        return this;
    }

    /** Ikonli literal, e.g. {@code fth-play}. */
    public Action icon(String iconLiteral) {
        this.iconLiteral = iconLiteral;
        return this;
    }

    /** JavaFX accelerator text such as {@code shortcut+shift+N}; {@code shortcut} is Ctrl or Cmd. */
    public Action shortcut(String shortcut) {
        this.shortcut = shortcut;
        return this;
    }

    /** Where it goes in the menu bar: {@code File}, {@code Navigate/Go To}, ... ; null for none. */
    public Action menu(String menuPath) {
        this.menuPath = menuPath;
        return this;
    }

    /** Puts it on the main toolbar, in the named group. */
    public Action toolbar(String group) {
        this.toolbarGroup = group;
        return this;
    }

    /**
     * Adds it to a context menu: {@code explorer} or {@code editor}.
     *
     * <p>Call it more than once for an action that belongs in both. It used to replace
     * rather than add, which silently dropped the first of two.
     */
    public Action contextMenu(String which) {
        contextMenus.add(which);
        return this;
    }

    /** Sort key within its menu; lower comes first. */
    public Action order(int order) {
        this.order = order;
        return this;
    }

    public Action enabledWhen(Predicate<ActionContext> predicate) {
        this.enabled = predicate;
        return this;
    }

    public Action perform(Consumer<ActionContext> handler) {
        this.handler = handler;
        return this;
    }

    public String id() {
        return id;
    }

    public String text() {
        return text;
    }

    public String description() {
        return description;
    }

    public String iconLiteral() {
        return iconLiteral;
    }

    public String shortcut() {
        return shortcut;
    }

    public String menuPath() {
        return menuPath;
    }

    public String toolbarGroup() {
        return toolbarGroup;
    }

    /** The first context menu it was added to, or null; prefer {@link #inContextMenu}. */
    public String contextMenu() {
        return contextMenus.isEmpty() ? null : contextMenus.iterator().next();
    }

    public boolean inContextMenu(String which) {
        return contextMenus.contains(which);
    }

    public int order() {
        return order;
    }

    public boolean isEnabled(ActionContext context) {
        try {
            return enabled.test(context);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void run(ActionContext context) {
        handler.accept(context);
    }
}
