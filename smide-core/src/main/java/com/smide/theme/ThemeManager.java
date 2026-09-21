package com.smide.theme;

import com.smide.api.settings.Settings;
import com.smide.api.ui.Theme;
import com.smide.api.util.EventBus;
import com.smide.api.util.Events;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One class on the scene root, {@code dark-theme}, switches every colour in the
 * stylesheet. Windows a plugin opens are styled on request and re-styled when the
 * theme flips, for as long as they are showing.
 */
public final class ThemeManager implements Theme {

    public static final String DARK_CLASS = "dark-theme";
    public static final String SETTING_KEY = "appearance.dark";

    private static final Map<String, String> LIGHT = Map.ofEntries(
            Map.entry("paper", "#f6f8fa"), Map.entry("surface", "#ffffff"), Map.entry("surface-alt", "#eef2f6"),
            Map.entry("border", "#dfe5ec"), Map.entry("text", "#16202b"), Map.entry("text-muted", "#5a6875"),
            Map.entry("accent", "#0b6e7f"), Map.entry("accent-soft", "#dceef1"), Map.entry("danger", "#C0392B"),
            Map.entry("warning", "#B7791F"), Map.entry("success", "#2F855A"), Map.entry("selection", "#d5e5f2"),
            Map.entry("scroll-thumb", "#aebbc9"), Map.entry("scroll-track", "#f6f8fa"),
            Map.entry("current-line", "#eef3f7"));
    private static final Map<String, String> DARK = Map.ofEntries(
            Map.entry("paper", "#0f1620"), Map.entry("surface", "#131c26"), Map.entry("surface-alt", "#16212d"),
            Map.entry("border", "#22303f"), Map.entry("text", "#d7dee6"), Map.entry("text-muted", "#8a9aaa"),
            Map.entry("accent", "#3fb8cc"), Map.entry("accent-soft", "#16323c"), Map.entry("danger", "#E06C5B"),
            Map.entry("warning", "#D6A24A"), Map.entry("success", "#5FBF8A"), Map.entry("selection", "#243b52"),
            Map.entry("scroll-thumb", "#3d4f63"), Map.entry("scroll-track", "#0f1620"),
            Map.entry("current-line", "#141e29"));

    private final SimpleBooleanProperty dark = new SimpleBooleanProperty(false);
    private final Settings settings;
    private final EventBus events;
    private final String stylesheet;
    private final List<Parent> roots = new ArrayList<>();

    public ThemeManager(Settings settings, EventBus events) {
        this.settings = settings;
        this.events = events;
        this.stylesheet = ThemeManager.class.getResource("/css/smide.css").toExternalForm();
        dark.set(settings.getBoolean(SETTING_KEY, false));
        dark.addListener((obs, was, now) -> {
            settings.setBoolean(SETTING_KEY, now);
            for (Parent root : List.copyOf(roots)) {
                apply(root, now);
            }
            events.publish(new Events.ThemeChanged(now));
        });
    }

    @Override
    public boolean isDark() {
        return dark.get();
    }

    @Override
    public void setDark(boolean value) {
        dark.set(value);
    }

    @Override
    public ReadOnlyBooleanProperty darkProperty() {
        return dark;
    }

    @Override
    public void style(Window window) {
        if (window == null || window.getScene() == null) {
            return;
        }
        Scene scene = window.getScene();
        if (!scene.getStylesheets().contains(stylesheet)) {
            scene.getStylesheets().add(stylesheet);
        }
        style(scene.getRoot());
    }

    @Override
    public void style(Parent root) {
        if (root == null) {
            return;
        }
        apply(root, dark.get());
        if (!roots.contains(root)) {
            roots.add(root);
            // Drop the reference once the node leaves its scene: dialogs come and go.
            root.sceneProperty().addListener((obs, was, now) -> {
                if (now == null) {
                    roots.remove(root);
                }
            });
        }
    }

    private static void apply(Parent root, boolean isDark) {
        if (isDark) {
            if (!root.getStyleClass().contains(DARK_CLASS)) {
                root.getStyleClass().add(DARK_CLASS);
            }
        } else {
            root.getStyleClass().remove(DARK_CLASS);
        }
    }

    @Override
    public String stylesheet() {
        return stylesheet;
    }

    @Override
    public String color(String token) {
        return (dark.get() ? DARK : LIGHT).getOrDefault(token, "#ff00ff");
    }
}
