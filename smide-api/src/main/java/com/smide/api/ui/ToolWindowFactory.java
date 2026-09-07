package com.smide.api.ui;

import javafx.scene.Node;

/**
 * A panel docked to an edge of the window, with a tab on the tool stripe. One instance
 * of the content exists for the life of the window; it is shown and hidden, not rebuilt.
 */
public interface ToolWindowFactory {

    String id();

    String title();

    String iconLiteral();

    ToolWindowAnchor anchor();

    /** {@code alt+1} and the like; null for none. */
    default String shortcut() {
        return null;
    }

    /** Position on the stripe; lower is nearer the top or the left. */
    default int order() {
        return 100;
    }

    Node create(ToolWindowContext context);

    interface ToolWindowContext {
        /** Changes the tab's title, for a count or a state. */
        void setTitle(String title);

        void show();

        void hide();
    }
}
