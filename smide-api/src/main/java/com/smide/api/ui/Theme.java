package com.smide.api.ui;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.scene.Parent;
import javafx.stage.Window;

/**
 * Light or dark, from one token block. Plugins that open their own windows call
 * {@link #style(Window)} so dialogs match; nodes inside the main scene need nothing.
 */
public interface Theme {

    boolean isDark();

    void setDark(boolean dark);

    ReadOnlyBooleanProperty darkProperty();

    /** Applies the application stylesheet and theme class to a window the plugin opened. */
    void style(Window window);

    /** Applies the theme class to a root node in a scene of its own (a popup, a SwingNode host). */
    void style(Parent root);

    /** The application stylesheet URL, for a plugin's own scene. */
    String stylesheet();

    /** A CSS colour string for a token such as {@code accent}, {@code paper}, {@code text}. */
    String color(String token);
}
