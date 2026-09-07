package com.smide.api.ui;

import javafx.scene.Node;

/** Something small on the status bar: a language-server state, a Git branch, a JDK. */
public interface StatusBarWidget {

    String id();

    Node node();

    /** Right side, lower first. */
    default int order() {
        return 100;
    }
}
