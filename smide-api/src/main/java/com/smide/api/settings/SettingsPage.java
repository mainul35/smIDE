package com.smide.api.settings;

import javafx.scene.Node;

/** A page in the Settings dialog, placed by its path such as {@code Languages/Java}. */
public interface SettingsPage {

    String path();

    /** Builds the form; changes are written to settings on Apply. */
    Node create(SettingsEditor editor);

    /** Words Find in Settings matches against. */
    default String keywords() {
        return "";
    }

    /**
     * Hands the page a way to stage changes: values set here are written to the real
     * settings when the user presses OK or Apply, and dropped on Cancel.
     */
    interface SettingsEditor {
        Settings staged();

        void onApply(Runnable callback);
    }
}
